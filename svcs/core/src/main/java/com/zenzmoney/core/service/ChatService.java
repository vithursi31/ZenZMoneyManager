package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionStatus;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.i18n.ChatText;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.ChatMessageRepository;
import com.zenzmoney.core.repository.TransactionRepository;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import com.zenzmoney.core.service.insight.SpendingSnapshotService;
import com.zenzmoney.core.service.llm.IntentResolver;
import com.zenzmoney.core.service.llm.LlmAdviceClient;
import com.zenzmoney.core.service.llm.LlmExtraction;
import com.zenzmoney.core.service.llm.LlmExtractionBatch;
import com.zenzmoney.core.service.llm.LlmExtractionClient;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import com.zenzmoney.core.web.dto.ChatMessageResponse;
import com.zenzmoney.core.web.dto.ChatPromptView;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.ChatResultView;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.RecurringCreatedResponse;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateChatDraftRequest;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The capture pipeline for chat-entered transactions (F-1.11).
 *
 * <p><b>A message the model read confidently and completely is written.</b> There is
 * no confirm step in front of it: asking a user to approve what they just typed is a
 * tap that adds nothing, and the sample conversation this flow is built to match
 * shows the entry appearing straight away. What makes that safe is that the write is
 * reversible — {@link #undo} deletes it — so a misread amount costs one tap to
 * correct rather than being a wrong row nobody notices. A draft the model <em>was</em>
 * unsure of is written all the same — <b>there is no confidence gate</b> (removed
 * 2026-09-03). A reading the model doubts is one it should have declined outright, and
 * the prompt tells it to: below 0.4 it answers {@code UNKNOWN} and nothing is captured.
 * A second, backend-side threshold only ever turned a usable capture into a tap.
 * {@link ChatMessageStatus#PARSED} survives for the two cases where asking is the
 * point: a suspected duplicate, and a delete waiting to be confirmed.
 *
 * <p><b>One message may hold several entries.</b> "$28 on coffee, $350 on groceries
 * and $120 on fuel" is three, and only the model can pair the amounts with the nouns.
 * Each becomes its own assistant turn with its own status and its own way back, which
 * is also how the conversation reads: three answers, not one listing three things.
 *
 * <p><b>A question is answered, not captured.</b> A message the model reads as a
 * {@link IntentType#QUERY} takes a second pass: the ledger figures are aggregated
 * here and handed to {@link LlmAdviceClient} with the question, so the model writes
 * the sentence and never the arithmetic. Nothing is written to the ledger, and any
 * capture already in progress is left exactly where it was.
 *
 * <p><b>A conversation refines one draft.</b> A turn that leaves a draft short asks
 * about exactly one missing field, and whatever comes back folds into the same draft
 * rather than starting a new one — the turn it grew out of is marked
 * {@link ChatMessageStatus#SUPERSEDED} so a conversation never holds two open drafts
 * at once.
 *
 * <p><b>Every sentence here is a message key, not a sentence.</b> What goes into
 * {@code chat_message.content} and out on the response is {@code chat.added} or
 * {@code chat.ask.amount}; {@link ChatText} renders it at the boundary, in the
 * caller's language (§0.5). The one exception is an answer the model wrote, which is
 * prose by nature and is stored as it came back.
 */
@Service
public class ChatService {

    /**
     * Routed to llm.log alongside OllamaExtractionClient, so one file holds the whole extraction
     * path: the message went in, what came back, and whether the user kept what it wrote. The
     * message text itself is NOT logged — it is free-form user input about their own finances.
     */
    private static final Logger log = AppLog.LLM;

    /** {@code chat_message.content} is VARCHAR(2000); a runaway generation must not break the insert. */
    private static final int MAX_REPLY_CHARS = 2000;

    /** The states a draft can still be taken further from — every other one is terminal. */
    private static final Set<ChatMessageStatus> LIVE =
            EnumSet.of(ChatMessageStatus.PARSED, ChatMessageStatus.NEEDS_CLARIFICATION);

    /** The states that wrote something, and can therefore be undone. */
    private static final Set<ChatMessageStatus> WROTE =
            EnumSet.of(ChatMessageStatus.CREATED, ChatMessageStatus.CONFIRMED);

    /** How far back a delete request looks for the row the user means. */
    private static final long DELETE_LOOKBACK_MILLIS = Duration.ofDays(90).toMillis();

    /**
     * How close in time two identical entries have to be before the second is worth
     * querying. A day either side catches the double-entry this is for ("add my Netflix
     * payment" twice) without ever flagging the legitimate monthly repeat 30 days later.
     */
    private static final long DUPLICATE_WINDOW_MILLIS = Duration.ofDays(1).toMillis();

    /** The intents that produce a draft. */
    private static final Set<IntentType> CAPTURE =
            EnumSet.of(IntentType.CREATE_TRANSACTION, IntentType.CREATE_RECURRING);

    /**
     * Chat costs CPU — a self-hosted model saturates cores for seconds per call —
     * so it is throttled per user and fails <b>closed</b>: if Redis is unreachable
     * the request is denied rather than allowed to hammer the model.
     */
    private static final RateLimitPolicy CHAT_POLICY = RateLimitPolicy
            .of(10, Duration.ofMinutes(1))
            .and(100, Duration.ofHours(1))
            .and(500, Duration.ofDays(1));

    /**
     * Answering a question costs a <em>second</em> model call, on a longer prompt and
     * a far longer generation than extraction — several times the compute of a
     * capture. It gets its own tighter budget rather than riding on
     * {@link #CHAT_POLICY}, and fails <b>closed</b> for the same reason that one does.
     */
    private static final RateLimitPolicy INSIGHT_POLICY = RateLimitPolicy
            .of(5, Duration.ofMinutes(1))
            .and(30, Duration.ofHours(1))
            .and(100, Duration.ofDays(1));

    private final LlmExtractionClient llmClient;
    private final LlmAdviceClient adviceClient;
    private final IntentResolver intentResolver;
    private final ChatSuggestions suggestions;
    private final SpendingSnapshotService snapshotService;
    private final ChatMessageRepository chatMessageRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final RecurringTransactionService recurringService;
    private final CurrentUserService currentUser;
    private final RedisRateLimitService rateLimitService;
    private final ChatText chatText;

    public ChatService(LlmExtractionClient llmClient,
                       LlmAdviceClient adviceClient,
                       IntentResolver intentResolver,
                       ChatSuggestions suggestions,
                       SpendingSnapshotService snapshotService,
                       ChatMessageRepository chatMessageRepository,
                       CategoryRepository categoryRepository,
                       TransactionRepository transactionRepository,
                       TransactionService transactionService,
                       RecurringTransactionService recurringService,
                       CurrentUserService currentUser,
                       RedisRateLimitService rateLimitService,
                       ChatText chatText) {
        this.llmClient = llmClient;
        this.adviceClient = adviceClient;
        this.intentResolver = intentResolver;
        this.suggestions = suggestions;
        this.snapshotService = snapshotService;
        this.chatMessageRepository = chatMessageRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.recurringService = recurringService;
        this.currentUser = currentUser;
        this.rateLimitService = rateLimitService;
        this.chatText = chatText;
    }

    /**
     * Reads one message — on its own, or as the answer to the question the last turn
     * asked — and records everything in it that is complete.
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> The model call takes
     * seconds; holding a pooled DB connection across it is how the pool starves
     * under load. The transcript rows and each ledger write are therefore separate
     * short transactions after the slow work, which also means one item failing to
     * write cannot roll back the ones that already did.
     */
    public ChatReplyResponse handle(ChatRequest request) {
        User user = currentUser.requireUser();

        RateLimitResult limit = rateLimitService.tryConsumeOrDeny("chat:" + user.getId(), CHAT_POLICY);
        if (!limit.allowed()) {
            throw new TooManyRequestsException(ServiceCodes.SC_CHAT_RATE_LIMIT_EXCEEDED.with(Msg.CHAT_RATE_LIMITED),
                    limit.retryAfterSeconds());
        }

        String message = request.getMessage().trim();
        boolean continuing = request.getSessionId() != null && !request.getSessionId().isBlank();
        String sessionId = continuing ? request.getSessionId().trim() : UUID.randomUUID().toString();

        // Only the user's own category names leave the app, and only to a model we
        // host (§9 privacy). Sorted so the same message always builds the same prompt.
        List<String> categoryNames = categoryRepository
                .findByUserIdAndStatus(user.getId(), CategoryStatus.ACTIVE).stream()
                .map(Category::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        // A conversation that starts with this message has nothing open to look up.
        ChatMessage pendingTurn = continuing ? liveTurn(user.getId(), sessionId) : null;
        ParsedIntent pendingDraft = pendingTurn == null ? null : pendingTurn.getParsedIntent();
        String conversation = continuing ? conversation(user.getId(), sessionId) : null;

        long startedAt = System.currentTimeMillis();
        LlmExtractionBatch batch = llmClient.extract(message, categoryNames, conversation);

        // A question is a different job from a capture: answer it from the ledger and
        // leave any draft in progress untouched.
        if (batch.getIntent() == IntentType.QUERY) {
            return answerQuestion(user, sessionId, message, startedAt);
        }

        record(user, sessionId, ChatRole.USER, message, null, ChatMessageStatus.RECEIVED);

        // Removing something is the opposite of capturing it, and reading it as a capture
        // would *add* what the user asked to take away — the worst possible misreading.
        if (batch.getIntent() == IntentType.DELETE_TRANSACTION) {
            return proposeDelete(user, sessionId, batch, startedAt);
        }
        return capture(user, sessionId, message, batch, pendingTurn, pendingDraft,
                categoryNames.size(), startedAt);
    }

    /**
     * Turns everything the model read into turns, writing what is complete and asking
     * about at most one thing.
     *
     * <p><b>Only one turn may be left open.</b> The conversation has a single slot for
     * an unanswered question — {@link #liveTurn} looks up exactly one — so when a
     * message leaves two entries short, the first is asked about and the rest are said
     * to be unread rather than queued behind it. Queuing them would be a second
     * conversation state machine for a case that barely occurs.
     */
    private ChatReplyResponse capture(User user, String sessionId, String message,
                                      LlmExtractionBatch batch, ChatMessage pendingTurn,
                                      ParsedIntent pendingDraft, int categoryCount, long startedAt) {
        List<LlmExtraction> readings = readingsOf(batch);
        // "one ticket 50 for snacks 10" is ONE night out, not two purchases — every amount
        // in an answer belongs to the event already under discussion. A fresh message
        // naming several amounts still splits (prompt Golden Rule #3); the open question is
        // the only thing that tells the two apart, which is why this is keyed on it.
        long partsMinor = foldableParts(readings, pendingDraft, user.getActiveCurrency());
        if (partsMinor > 0) {
            readings = List.of(mergeParts(readings));
        }
        List<ChatResultView> results = new ArrayList<>();
        ChatMessage lastTurn = null;
        ChatPromptView lastPrompt = null;
        boolean carriedForward = false;
        boolean openTaken = false;
        int unread = 0;

        for (int i = 0; i < readings.size(); i++) {
            // Only the first reading can be the answer to a pending question: a second
            // amount in the same message is a new event, not a continuation.
            ParsedIntent draft = intentResolver.resolve(
                    user, message, readings.get(i), i == 0 ? pendingDraft : null, readings.size() > 1);
            if (partsMinor > 0) {
                // The parts are the amount. Summed here rather than in the prompt because
                // arithmetic on someone's money is not something a 1.5B model should own.
                draft.setAmountMinor(partsMinor);
                intentResolver.revalidate(draft);
            }

            ChatMessageStatus status = statusFor(batch, draft);
            if (LIVE.contains(status)) {
                if (openTaken) {
                    unread++;
                    continue;
                }
                openTaken = true;
            }

            ChatPromptView prompt = status == ChatMessageStatus.FAILED
                    ? null
                    : suggestions.promptFor(draft);

            String transactionId = null;
            String recurringId = null;
            if (status == ChatMessageStatus.CREATED && isDuplicate(user.getId(), draft)) {
                // Not refused — queried. Recording the same coffee twice in a day is a real
                // thing people do, so the answer has to be the user's, not ours.
                draft.setDuplicateSuspected(true);
                status = ChatMessageStatus.PARSED;
            }
            if (status == ChatMessageStatus.CREATED) {
                Written written = write(draft);
                if (written == null) {
                    // The ledger refused what the resolver thought it would accept. Keep the
                    // draft confirmable rather than losing it, and leave the line in the log.
                    status = ChatMessageStatus.PARSED;
                } else {
                    transactionId = written.transactionId();
                    recurringId = written.recurringId();
                }
            }

            ChatMessage turn = turn(user, sessionId, ChatRole.ASSISTANT,
                    replyFor(draft, status, prompt), status == ChatMessageStatus.FAILED ? null : draft,
                    status);
            turn.setTransactionId(transactionId);
            turn.setRecurringId(recurringId);
            results.add(ChatResultView.of(chatMessageRepository.save(turn), prompt));
            lastTurn = turn;
            lastPrompt = prompt;
            carriedForward |= status != ChatMessageStatus.FAILED && CAPTURE.contains(draft.getIntent());
        }

        if (unread > 0) {
            // Its own turn rather than a clause on the question, so the question the client
            // renders stays exactly the sentence ChatSuggestions decided.
            ChatMessage note = record(user, sessionId, ChatRole.ASSISTANT,
                    Msg.CHAT_REST_UNREAD.key(), null, ChatMessageStatus.FAILED);
            results.add(ChatResultView.of(note, null));
            log.warn("Chat message left {} of {} entries unread — only one question can be open "
                    + "(user {}, session {})", unread, readings.size(), user.getId(), sessionId);
        }

        // Shape of the outcome, not the content: message length rather than the message, so a
        // confidence or resolution regression is diagnosable without logging what the user typed.
        log.info("Chat extraction: intent={} items={} written={} asking={} failed={} continuing={} "
                        + "chars={} categories={} in {}ms (user {}, session {})",
                batch.getIntent(), readings.size(),
                results.stream().filter(r -> r.getStatus() == ChatMessageStatus.CREATED).count(),
                lastPrompt == null ? "nothing" : lastPrompt.getField(), batch.isFailed(),
                pendingDraft != null, message.length(), categoryCount,
                System.currentTimeMillis() - startedAt, user.getId(), sessionId);

        // Only once the new turn actually carries the capture forward. A model outage or a
        // change of subject must leave the old draft live, or the user loses work they can
        // neither see nor recover.
        if (carriedForward) {
            supersede(pendingTurn, lastTurn == null ? null : lastTurn.getId());
        }
        return ChatReplyResponse.of(lastTurn, lastPrompt, results);
    }

    /**
     * Applies a preview edit to a draft that has not been written yet.
     *
     * <p>No model call: the user picked these values, so re-reading them as language
     * would add a second of latency and a chance to get them wrong. It is also why
     * this path is not rate-limited — it costs a row update, not inference.
     *
     * <p><b>It does not write, even when the edit completes the draft.</b> The preview
     * is a deliberate review gesture and ends at its own Create button; a second write
     * path out of the same screen would leave the user unsure which one committed.
     * Typing the answer instead goes through {@link #handle}, which does write.
     */
    @Transactional
    public ChatReplyResponse amendDraft(UpdateChatDraftRequest request) {
        User user = currentUser.requireUser();
        ChatMessage turn = requireOwned(request.getMessageId(), user.getId());
        if (WROTE.contains(turn.getStatus())) {
            throw new BadRequestException(Msg.CHAT_DRAFT_COMMITTED);
        }
        if (!LIVE.contains(turn.getStatus())) {
            throw new BadRequestException(Msg.CHAT_DRAFT_CLOSED);
        }
        ParsedIntent draft = turn.getParsedIntent();
        if (draft == null || !CAPTURE.contains(draft.getIntent())) {
            throw new BadRequestException(Msg.CHAT_NO_DRAFT_TO_CHANGE);
        }

        apply(user, draft, request);
        intentResolver.revalidate(draft);

        ChatMessageStatus status = draft.isComplete()
                ? ChatMessageStatus.PARSED
                : ChatMessageStatus.NEEDS_CLARIFICATION;
        ChatPromptView prompt = suggestions.promptFor(draft);
        turn.setParsedIntent(draft);
        turn.setStatus(status);
        turn.setContent(replyFor(draft, status, prompt));
        chatMessageRepository.save(turn);

        // The draft is what the user is about to commit, so every edit to it is a state change
        // worth a line — amounts and ids only, never the note or payee text they typed.
        log.info("Chat draft amended: status={} intent={} type={} amount={} category={} cadence={} "
                        + "asking={} (message {}, user {})",
                status, draft.getIntent(), draft.getTxnType(), draft.getAmountMinor(),
                draft.getCategoryId(), draft.getCadence(),
                prompt == null ? "nothing" : prompt.getField(), turn.getId(), user.getId());
        return ChatReplyResponse.of(turn, prompt);
    }

    /**
     * Writes a draft the model was not confident enough to write on its own. The
     * ordinary path never reaches here — {@link #handle} has already written anything
     * complete and confident.
     */
    @Transactional
    public ChatReplyResponse confirm(String messageId) {
        User user = currentUser.requireUser();
        ChatMessage turn = requireOwned(messageId, user.getId());
        if (WROTE.contains(turn.getStatus())) {
            throw new BadRequestException(Msg.CHAT_DRAFT_ADDED);
        }
        if (turn.getStatus() == ChatMessageStatus.SUPERSEDED) {
            throw new BadRequestException(Msg.CHAT_DRAFT_SUPERSEDED);
        }
        if (turn.getStatus() != ChatMessageStatus.PARSED) {
            throw new BadRequestException(Msg.CHAT_NO_DRAFT_TO_CONFIRM);
        }
        ParsedIntent draft = turn.getParsedIntent();
        if (draft == null) {
            throw new BadRequestException(Msg.CHAT_DRAFT_INCOMPLETE);
        }

        // A delete turn proposes a removal rather than a row, so confirming it removes.
        if (draft.getIntent() == IntentType.DELETE_TRANSACTION) {
            boolean retired = transactionService.deleteIfLive(draft.getTargetTransactionId());
            turn.setStatus(ChatMessageStatus.REMOVED);
            turn.setContent(retired ? Msg.CHAT_DELETE_DONE.key() : Msg.CHAT_DELETE_ALREADY_GONE.key());
            chatMessageRepository.save(turn);
            log.info("Chat delete confirmed: message {} -> txn {} retired={}",
                    messageId, draft.getTargetTransactionId(), retired);
            return ChatReplyResponse.of(turn, null);
        }

        if (!draft.isComplete() || draft.getAmountMinor() == null) {
            throw new BadRequestException(Msg.CHAT_DRAFT_INCOMPLETE);
        }

        Written written = write(draft);
        if (written == null) {
            throw new BadRequestException(Msg.CHAT_DRAFT_INCOMPLETE);
        }
        turn.setStatus(ChatMessageStatus.CONFIRMED);
        turn.setTransactionId(written.transactionId());
        turn.setRecurringId(written.recurringId());
        turn.setContent(replyFor(draft, ChatMessageStatus.CONFIRMED, null));
        chatMessageRepository.save(turn);
        // The ledger write itself is logged by TransactionService; this records that a *chat draft*
        // was what produced it, which is the link the txn line cannot carry.
        log.info("Chat draft confirmed to ledger: message {} -> txn {} recurring {}",
                messageId, written.transactionId(), written.recurringId());
        return ChatReplyResponse.of(turn, null);
    }

    /**
     * Takes back what a chat turn wrote. The way back that makes writing without asking
     * safe — a misread amount is one tap to correct rather than a wrong row the user
     * has to go and find.
     *
     * <p><b>Nothing here destroys a row.</b> A transaction is retired to
     * {@link com.zenzmoney.common.domain.TransactionStatus#DELETED} (§1.6) and a
     * template is <em>deactivated</em>, never deleted — chat is allowed to stop a
     * repeating payment it created, and not to remove one. Both steps are idempotent,
     * so a row the user already removed by hand cannot leave undo half-applied with the
     * template still generating, which is what a strict delete on each did.
     *
     * <p>A template already due posted its first occurrence when it was created, so
     * both are dealt with: the occurrence is retired and the template stopped.
     */
    @Transactional
    public void undo(String messageId) {
        String userId = currentUser.requireUserId();
        ChatMessage turn = requireOwned(messageId, userId);
        if (turn.getStatus() == ChatMessageStatus.UNDONE) {
            throw new BadRequestException(Msg.CHAT_ALREADY_UNDONE);
        }
        // Undoing a removal is a restore — which is only possible because deleting is soft.
        if (turn.getStatus() == ChatMessageStatus.REMOVED) {
            ParsedIntent removed = turn.getParsedIntent();
            String targetId = removed == null ? null : removed.getTargetTransactionId();
            boolean restored = targetId != null && transactionService.restoreIfDeleted(targetId);
            turn.setStatus(ChatMessageStatus.UNDONE);
            turn.setContent(Msg.CHAT_RESTORED.key());
            chatMessageRepository.save(turn);
            log.info("Chat removal undone: message {} -> txn {} restored={} (user {})",
                    messageId, targetId, restored, userId);
            return;
        }
        if (!WROTE.contains(turn.getStatus())) {
            throw new BadRequestException(Msg.CHAT_NOTHING_TO_UNDO);
        }

        // Both services scope by the caller, and the ids came off a turn this user owns —
        // so there is no id here that could reach another user's row.
        boolean retired = turn.getTransactionId() != null
                && transactionService.deleteIfLive(turn.getTransactionId());
        boolean stopped = turn.getRecurringId() != null
                && recurringService.deactivateIfActive(turn.getRecurringId());

        turn.setStatus(ChatMessageStatus.UNDONE);
        turn.setContent(turn.getRecurringId() != null
                ? Msg.CHAT_UNDONE_RECURRING.key()
                : Msg.CHAT_UNDONE.key());
        chatMessageRepository.save(turn);
        log.info("Chat write undone: message {} (txn {} retired={}, recurring {} stopped={}, user {})",
                messageId, turn.getTransactionId(), retired,
                turn.getRecurringId(), stopped, userId);
    }

    /** Discards a draft that was never written. The turn is kept for history. */
    @Transactional
    public void reject(String messageId) {
        ChatMessage turn = requireOwned(messageId, currentUser.requireUserId());
        if (WROTE.contains(turn.getStatus())) {
            throw new BadRequestException(Msg.CHAT_DRAFT_COMMITTED_REJECT);
        }
        if (!LIVE.contains(turn.getStatus())) {
            throw new BadRequestException(Msg.CHAT_DRAFT_CLOSED_DISCARD);
        }
        turn.setStatus(ChatMessageStatus.REJECTED);
        chatMessageRepository.save(turn);
        // Rejections are the signal that extraction quality is off — worth counting over time.
        log.info("Chat draft rejected: message {}", messageId);
    }

    /**
     * Replays one conversation, oldest turn first. Only the live turn carries its
     * question: every earlier one has already been answered, and re-offering it would
     * invite the user to answer it twice.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> history(String sessionId) {
        String userId = currentUser.requireUserId();
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ChatMessage> turns = chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreatedTimeAsc(userId, sessionId.trim()).stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        ChatMessage live = turns.stream()
                .filter(t -> t.getRole() == ChatRole.ASSISTANT && LIVE.contains(t.getStatus()))
                .reduce((first, second) -> second)
                .orElse(null);

        return turns.stream()
                .map(t -> t == live
                        ? ChatMessageResponse.of(t, suggestions.promptFor(t.getParsedIntent()))
                        : ChatMessageResponse.of(t))
                .toList();
    }

    /**
     * True when the user already has a live row this draft would repeat.
     *
     * <p>Matched on amount, category, direction and a day either side — the shape of an
     * accidental second entry. Deliberately not on payee or note: the case this exists
     * for is the user saying the same thing twice in slightly different words, so
     * matching the words would miss it.
     *
     * <p>Only a one-off is checked. Creating the same template twice is caught by the
     * user seeing it in their subscriptions, and a template that legitimately matches an
     * existing transaction (this month's charge) must not be blocked by it.
     */
    private boolean isDuplicate(String userId, ParsedIntent draft) {
        if (draft.getIntent() != IntentType.CREATE_TRANSACTION
                || draft.getAmountMinor() == null || draft.getCategoryId() == null) {
            return false;
        }
        long when = draft.getTxnDate() == null ? System.currentTimeMillis() : draft.getTxnDate();
        return transactionRepository
                .findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                        userId, TransactionStatus.ACTIVE, draft.getAmountMinor()).stream()
                .anyMatch(t -> draft.getCategoryId().equals(t.getCategoryId())
                        && t.getType() == draft.getTxnType()
                        && Math.abs(t.getTxnDate() - when) <= DUPLICATE_WINDOW_MILLIS);
    }

    // --- removing (F-1.11) ---

    /**
     * Finds the row a delete request names and asks the user to confirm it — never
     * removes anything on this turn.
     *
     * <p><b>The one place chat still asks before acting.</b> Creating is safe to do
     * unasked because undo is one tap; removing is not the same bet, because the row the
     * model picked may not be the row the user meant. So the match is offered and the
     * user confirms it, and only an <em>exact</em> amount match is offered at all —
     * anything looser would put someone else's transaction in front of a Yes button.
     */
    private ChatReplyResponse proposeDelete(User user, String sessionId,
                                            LlmExtractionBatch batch, long startedAt) {
        LlmExtraction reading = batch.first();
        Long amount = IntentResolver.toMinorUnits(reading.getAmountRaw(), user.getActiveCurrency());

        List<Transaction> candidates = amount == null ? List.of() : transactionRepository
                .findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                        user.getId(), TransactionStatus.ACTIVE, amount).stream()
                .filter(t -> t.getTxnDate() >= System.currentTimeMillis() - DELETE_LOOKBACK_MILLIS)
                .toList();

        ChatMessageStatus status;
        String reply;
        ParsedIntent draft = null;
        if (candidates.size() == 1) {
            Transaction target = candidates.get(0);
            draft = new ParsedIntent();
            draft.setIntent(IntentType.DELETE_TRANSACTION);
            draft.setTargetTransactionId(target.getId());
            draft.setTxnType(target.getType());
            draft.setAmountMinor(target.getAmount());
            draft.setCurrency(target.getCurrency());
            draft.setCategoryId(target.getCategoryId());
            draft.setTxnDate(target.getTxnDate());
            draft.setNote(target.getNote());
            draft.setConfidence(reading.getConfidence());
            status = ChatMessageStatus.PARSED;
            reply = Msg.CHAT_DELETE_CONFIRM.key();
        } else if (candidates.isEmpty()) {
            status = ChatMessageStatus.FAILED;
            reply = Msg.CHAT_DELETE_NOT_FOUND.key();
        } else {
            // Picking one of several on the model's word is exactly the guess worth
            // refusing: the list screen can show them all and the user can be sure.
            status = ChatMessageStatus.FAILED;
            reply = Msg.CHAT_DELETE_MANY.key();
        }

        log.info("Chat delete proposed: status={} amount={} candidates={} in {}ms (user {}, session {})",
                status, amount, candidates.size(),
                System.currentTimeMillis() - startedAt, user.getId(), sessionId);

        ChatMessage turn = record(user, sessionId, ChatRole.ASSISTANT, reply, draft, status);
        return ChatReplyResponse.of(turn, null);
    }

    // --- writing ---

    /** What one write produced. Either id may be null; both are never null together. */
    private record Written(String transactionId, String recurringId) {
    }

    /**
     * Writes the draft through the ordinary service path, so a chat-entered row gets
     * exactly the validation, payee resolution and logging a manually entered one does
     * (§1.6/§1.8).
     *
     * @return what was written, or null when the ledger refused it — which the caller
     *         turns into a confirmable draft rather than a lost one.
     */
    private Written write(ParsedIntent draft) {
        try {
            if (draft.getIntent() == IntentType.CREATE_RECURRING) {
                CreateRecurringRequest req = new CreateRecurringRequest();
                req.setType(draft.getTxnType());
                req.setCategoryId(draft.getCategoryId());
                req.setAmount(draft.getAmountMinor());
                req.setCadence(draft.getCadence());
                req.setNextRunDate(draft.getTxnDate());
                req.setPayeeName(draft.getPayeeName());
                req.setNote(draft.getNote());
                RecurringCreatedResponse created = recurringService.create(req);
                // A template already due posts its first occurrence in the same call, so undo
                // has two rows to remove and needs to know about both.
                return new Written(created.getPosted() == null ? null : created.getPosted().getId(),
                        created.getTemplate().getId());
            }

            CreateTransactionRequest req = new CreateTransactionRequest();
            req.setType(draft.getTxnType());
            req.setCategoryId(draft.getCategoryId());
            req.setAmount(draft.getAmountMinor());
            req.setTxnDate(draft.getTxnDate());
            req.setPayeeName(draft.getPayeeName());
            req.setNote(draft.getNote());
            TransactionResponse txn = transactionService.create(req);
            return new Written(txn.getId(), null);
        } catch (RuntimeException e) {
            // Expected and recoverable: the user is asked to confirm instead. Logged at WARN
            // because a resolver that produces drafts the ledger rejects is a real defect.
            log.warn("Chat write refused for a draft the resolver judged complete: intent={} type={} "
                            + "amount={} category={} cadence={} — {}",
                    draft.getIntent(), draft.getTxnType(), draft.getAmountMinor(),
                    draft.getCategoryId(), draft.getCadence(), e.toString());
            return null;
        }
    }

    // --- answering a question (F-1.16) ---

    /**
     * Answers a question about the user's own money from their own figures.
     *
     * <p><b>The backend does the arithmetic; the model does the sentence.</b> A
     * language model asked to total a ledger returns something plausible and wrong,
     * and a wrong figure about someone's money is worse than no answer — so the
     * snapshot is SQL aggregates ({@link SpendingSnapshotService}) and the prompt
     * forbids inventing a number. The same aggregates go back on the response, so the
     * client can show the breakdown the prose is describing.
     *
     * <p>Not {@code @Transactional}, and the snapshot is read before the model call
     * rather than around it — the generation takes seconds and must not hold a pooled
     * connection open while it runs.
     */
    private ChatReplyResponse answerQuestion(User user, String sessionId, String question, long startedAt) {
        SpendingSnapshot snapshot = snapshotService.snapshotFor(user);

        ChatMessageStatus status = ChatMessageStatus.ANSWERED;
        String reply;
        if (snapshot.isEmpty()) {
            // Nothing to reason about. Answering here beats paying for a model call that
            // could only say the same thing, less reliably.
            reply = Msg.CHAT_NO_SPENDING_YET.key();
        } else {
            RateLimitResult limit = rateLimitService.tryConsumeOrDeny(
                    "chat-insight:" + user.getId(), INSIGHT_POLICY);
            if (!limit.allowed()) {
                throw new TooManyRequestsException(ServiceCodes.SC_CHAT_RATE_LIMIT_EXCEEDED.with(Msg.CHAT_INSIGHT_RATE_LIMITED),
                        limit.retryAfterSeconds());
            }
            String answer = adviceClient.answer(question, snapshot);
            if (answer == null) {
                reply = Msg.CHAT_ANSWER_FAILED.key();
                status = ChatMessageStatus.FAILED;
            } else {
                // Prose the model wrote, in whatever language it answered — stored as it came
                // back, because there is no key that could stand for it.
                reply = truncate(answer);
            }
        }

        // Shape, not content: neither the question nor the answer text is logged — both are
        // the user's own financial detail. The category count is what says how much of their
        // ledger the model was shown.
        log.info("Chat question answered: status={} months={} categories={} questionChars={} "
                        + "answerChars={} in {}ms (user {}, session {})",
                status, snapshot.getMonths().size(),
                snapshot.getMonths().stream().mapToInt(m -> m.getCategories().size()).sum(),
                question.length(), reply.length(),
                System.currentTimeMillis() - startedAt, user.getId(), sessionId);

        record(user, sessionId, ChatRole.USER, question, null, ChatMessageStatus.RECEIVED);
        ChatMessage assistantTurn =
                record(user, sessionId, ChatRole.ASSISTANT, reply, null, status);
        return ChatReplyResponse.answered(assistantTurn, snapshot);
    }

    // --- internals ---

    /**
     * The total of every amount in the message, but only when it is answering an open
     * question — otherwise 0, and the readings are left to split as they are.
     *
     * @return the summed minor units, or 0 when there is nothing to fold.
     */
    private static long foldableParts(List<LlmExtraction> readings, ParsedIntent pending, String currency) {
        if (pending == null || readings.size() < 2) {
            return 0L;
        }
        long total = 0L;
        for (LlmExtraction reading : readings) {
            Long part = IntentResolver.toMinorUnits(reading.getAmountRaw(), currency);
            if (part != null) {
                total += part;
            }
        }
        return total;
    }

    /** One reading carrying the first item's fields and every item's note. */
    private static LlmExtraction mergeParts(List<LlmExtraction> readings) {
        LlmExtraction merged = readings.get(0);
        String notes = readings.stream()
                .map(LlmExtraction::getNote)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        if (!notes.isBlank()) {
            merged.setNote(notes);
        }
        return merged;
    }

    /**
     * What to resolve. An empty batch still gets one blank reading, because a message
     * the model gave up on can still be a capture — an answer to the open question, or
     * something plainly about money that the resolver recognises on its own.
     */
    private static List<LlmExtraction> readingsOf(LlmExtractionBatch batch) {
        if (!batch.isEmpty()) {
            return batch.getItems();
        }
        LlmExtraction blank = new LlmExtraction();
        blank.setIntent(batch.getIntent());
        blank.setFailed(batch.isFailed());
        return List.of(blank);
    }

    /**
     * The last two exchanges, oldest first, or null when the conversation is new.
     *
     * <p><b>A window rather than just the open question.</b> "one ticket 50 for snacks
     * 10" is unreadable on its own and obvious after "how much did you spend at the
     * movie?" — and the turn before that is what says the outing was entertainment. Two
     * exchanges is the smallest window that carries a question, its answer, and what
     * they were about; more would grow the prompt on every message for context the model
     * has already acted on.
     *
     * <p>Rendered in English because it is going into the model prompt — the same split
     * the logs use: the caller's language on the wire, {@code Locale.ENGLISH} everywhere
     * the reader is a machine. An assistant turn is stored as a key, so this is the one
     * place a stored key is resolved for something other than a user.
     */
    private String conversation(String userId, String sessionId) {
        List<ChatMessage> recent = chatMessageRepository
                .findTop4ByUserIdAndSessionIdOrderByCreatedTimeDesc(userId, sessionId);
        if (recent.isEmpty()) {
            return null;
        }
        UnaryOperator<String> english = chatText.forLocale(Locale.ENGLISH);
        StringBuilder out = new StringBuilder(256);
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage turn = recent.get(i);
            String text = turn.getRole() == ChatRole.USER
                    ? turn.getContent()
                    : english.apply(turn.getContent());
            if (text == null || text.isBlank()) {
                continue;
            }
            out.append(turn.getRole() == ChatRole.USER ? "user: " : "assistant: ")
                    .append(text.strip()).append('\n');
        }
        return out.isEmpty() ? null : out.toString().stripTrailing();
    }

    /** The conversation's open draft, or null when the next message starts fresh. */
    private ChatMessage liveTurn(String userId, String sessionId) {
        return chatMessageRepository
                .findFirstByUserIdAndSessionIdAndRoleAndStatusInOrderByCreatedTimeDesc(
                        userId, sessionId, ChatRole.ASSISTANT, LIVE)
                .orElse(null);
    }

    /**
     * Retires the draft a newer turn now carries. Without this a corrected draft would
     * leave its pre-correction self confirmable, and "actually make that 30" could still
     * be committed as 20 by a client that kept the old id.
     */
    private void supersede(ChatMessage pendingTurn, String replacementId) {
        if (pendingTurn == null) {
            return;
        }
        pendingTurn.setStatus(ChatMessageStatus.SUPERSEDED);
        chatMessageRepository.save(pendingTurn);
        log.info("Chat draft superseded: message {} -> {}", pendingTurn.getId(), replacementId);
    }

    /** Writes the request's set fields onto the draft, validating what the ledger would. */
    private void apply(User user, ParsedIntent draft, UpdateChatDraftRequest request) {
        if (request.getTxnType() != null) {
            draft.setTxnType(request.getTxnType());
        }
        if (request.getAmountMinor() != null) {
            draft.setAmountMinor(request.getAmountMinor());
        }
        if (request.getTxnDate() != null && request.getTxnDate() > 0) {
            draft.setTxnDate(request.getTxnDate());
        }
        if (request.getCadence() != null) {
            draft.setCadence(request.getCadence());
        }
        if (request.getNote() != null) {
            draft.setNote(trimToNull(request.getNote()));
        }
        if (request.getPayeeName() != null) {
            draft.setPayeeName(trimToNull(request.getPayeeName()));
        }
        applyCategory(user.getId(), draft, trimToNull(request.getCategoryId()));
    }

    /**
     * Sets the chosen category, or drops one the direction has just invalidated.
     *
     * <p>A category also settles the direction when nothing else has: picking "Salary"
     * can only mean income, and asking anyway would be asking a question the user has
     * already answered.
     */
    private void applyCategory(String userId, ParsedIntent draft, String categoryId) {
        if (categoryId != null) {
            Category category = categoryRepository
                    .findByIdAndUserIdAndStatus(categoryId, userId, CategoryStatus.ACTIVE)
                    .orElseThrow(() -> new NotFoundException(Msg.CATEGORY_NOT_FOUND));
            if (draft.getTxnType() == null) {
                draft.setTxnType(category.getKind() == CategoryKind.INCOME
                        ? TransactionType.INCOME
                        : TransactionType.EXPENSE);
            }
            if (category.getKind() != kindFor(draft.getTxnType())) {
                throw new BadRequestException(Msg.CATEGORY_KIND_MISMATCH);
            }
            draft.setCategoryId(category.getId());
            draft.setCategoryName(category.getName());
            return;
        }
        if (draft.getCategoryId() == null || draft.getTxnType() == null) {
            return;
        }
        // Flipping expense to income leaves the old category the wrong kind; clearing it
        // turns a write the ledger would refuse into the next question instead.
        boolean stillValid = categoryRepository
                .findByIdAndUserIdAndStatus(draft.getCategoryId(), userId, CategoryStatus.ACTIVE)
                .filter(c -> c.getKind() == kindFor(draft.getTxnType()))
                .isPresent();
        if (!stillValid) {
            draft.setCategoryId(null);
            draft.setCategoryName(null);
        }
    }

    /**
     * What becomes of one reading. {@link ChatMessageStatus#CREATED} means "write it";
     * the caller downgrades that to {@code PARSED} if the ledger refuses.
     *
     * <p>The confidence threshold is what separates a write from a question. Below it
     * the model has not earned the right to put a row in someone's ledger unasked, even
     * when it filled every field.
     */
    private static ChatMessageStatus statusFor(LlmExtractionBatch batch, ParsedIntent draft) {
        if (batch.isFailed()) {
            return ChatMessageStatus.FAILED;
        }
        if (!draft.isComplete()) {
            return ChatMessageStatus.NEEDS_CLARIFICATION;
        }
        return ChatMessageStatus.CREATED;
    }

    /**
     * The key for what the assistant says back — never a sentence, and never a
     * formatted amount: the draft carries the numbers and the client renders them.
     * When there is something to ask, the question is {@link ChatSuggestions}', so the
     * reply and the field it asks about are decided in one place.
     */
    private static String replyFor(ParsedIntent draft, ChatMessageStatus status, ChatPromptView prompt) {
        if (status == ChatMessageStatus.FAILED) {
            return Msg.CHAT_UNREADABLE.key();
        }
        if (status == ChatMessageStatus.CREATED || status == ChatMessageStatus.CONFIRMED) {
            return draft.getIntent() == IntentType.CREATE_RECURRING
                    ? Msg.CHAT_ADDED_RECURRING.key()
                    : Msg.CHAT_ADDED.key();
        }
        if (status == ChatMessageStatus.PARSED) {
            return draft.isDuplicateSuspected()
                    ? Msg.CHAT_DUPLICATE_SUSPECTED.key()
                    : Msg.CHAT_DRAFT_READY.key();
        }
        if (prompt != null) {
            return prompt.getQuestion();
        }

        if (draft.getIntent() == IntentType.UPDATE_TRANSACTION) {
            return Msg.CHAT_UPDATE_UNSUPPORTED.key();
        }
        if (draft.getIntent() == IntentType.DELETE_RECURRING) {
            // Chat can stop a repeating payment it created itself (undo), and nothing more:
            // removing one changes every future month, and picking the right template out
            // of "cancel my subscription" is exactly the guess not worth making.
            return Msg.CHAT_RECURRING_DELETE_UNSUPPORTED.key();
        }
        if (!CAPTURE.contains(draft.getIntent())) {
            return Msg.CHAT_NOTHING_TO_RECORD.key();
        }
        if (draft.getMissingFields().contains("currency")) {
            return Msg.CHAT_CURRENCY_UNSET.key();
        }
        // A complete draft the model itself doubted: nothing is missing to ask about.
        return Msg.CHAT_LOW_CONFIDENCE.key();
    }

    private ChatMessage record(User user, String sessionId, ChatRole role, String content,
                               ParsedIntent draft, ChatMessageStatus status) {
        return chatMessageRepository.save(turn(user, sessionId, role, content, draft, status));
    }

    /** The unsaved row, for a caller that still has ids to set on it before the insert. */
    private static ChatMessage turn(User user, String sessionId, ChatRole role, String content,
                                    ParsedIntent draft, ChatMessageStatus status) {
        ChatMessage turn = new ChatMessage();
        turn.setUserId(user.getId());
        turn.setSessionId(sessionId);
        turn.setRole(role);
        turn.setContent(content);
        turn.setLanguage(user.getLanguage());
        turn.setParsedIntent(draft);
        turn.setStatus(status);
        return turn;
    }

    private ChatMessage requireOwned(String messageId, String userId) {
        return chatMessageRepository.findByIdAndUserId(messageId, userId)
                .orElseThrow(() -> new NotFoundException(Msg.CHAT_MESSAGE_NOT_FOUND));
    }

    private static CategoryKind kindFor(TransactionType type) {
        return type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
    }

    private static String truncate(String reply) {
        return reply.length() <= MAX_REPLY_CHARS ? reply : reply.substring(0, MAX_REPLY_CHARS);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
