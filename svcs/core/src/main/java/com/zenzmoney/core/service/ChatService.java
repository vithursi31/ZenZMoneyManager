package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.ChatMessageRepository;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import com.zenzmoney.core.service.insight.SpendingSnapshotService;
import com.zenzmoney.core.service.llm.IntentResolver;
import com.zenzmoney.core.service.llm.LlmAdviceClient;
import com.zenzmoney.core.service.llm.LlmExtraction;
import com.zenzmoney.core.service.llm.LlmExtractionClient;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import com.zenzmoney.core.web.dto.ChatMessageResponse;
import com.zenzmoney.core.web.dto.ChatPromptView;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateChatDraftRequest;
import org.springframework.beans.factory.annotation.Value;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The capture pipeline for chat-entered transactions (chat entry plan §4/§5.5).
 *
 * <p><b>The model proposes; the user commits.</b> {@link #handle} never writes to
 * the ledger — it produces a draft and logs it. Only {@link #confirm}, an explicit
 * second call, creates a transaction. That gate is the whole safety story for AI
 * money entry (domain §3.7), which is why the confirmable state lives in the
 * persisted {@link ChatMessageStatus} rather than in anything the client sends.
 *
 * <p><b>A question is answered, not captured.</b> A message the model reads as a
 * {@link IntentType#QUERY} takes a second pass: the ledger figures are aggregated
 * here and handed to {@link LlmAdviceClient} with the question, so the model writes
 * the sentence and never the arithmetic. Nothing is written to the ledger, and any
 * capture already in progress is left exactly where it was.
 *
 * <p><b>A conversation refines one draft.</b> A first message rarely carries
 * everything — "I spent $20" says nothing about what for — so a turn that leaves
 * the draft short asks about exactly one missing field and offers answers for it
 * ({@link ChatSuggestions}). Whatever comes back, typed or tapped, folds into the
 * same draft rather than starting a new one, and the turn it grew out of is marked
 * {@link ChatMessageStatus#SUPERSEDED} so a conversation never holds two
 * confirmable drafts at once.
 */
@Service
public class ChatService {

    /**
     * Routed to llm.log alongside OllamaExtractionClient, so one file holds the whole extraction
     * path: the message went in, what came back, and whether the user accepted the draft. The
     * message text itself is NOT logged — it is free-form user input about their own finances.
     */
    private static final Logger log = AppLog.LLM;

    private static final String RATE_LIMIT_CODE = "E1052";

    /** {@code chat_message.content} is VARCHAR(2000); a runaway generation must not break the insert. */
    private static final int MAX_REPLY_CHARS = 2000;

    /** The states a draft can still be taken further from — every other one is terminal. */
    private static final Set<ChatMessageStatus> LIVE =
            EnumSet.of(ChatMessageStatus.PARSED, ChatMessageStatus.NEEDS_CLARIFICATION);

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
    private final TransactionService transactionService;
    private final CurrentUserService currentUser;
    private final RedisRateLimitService rateLimitService;
    private final double confidenceThreshold;

    public ChatService(LlmExtractionClient llmClient,
                       LlmAdviceClient adviceClient,
                       IntentResolver intentResolver,
                       ChatSuggestions suggestions,
                       SpendingSnapshotService snapshotService,
                       ChatMessageRepository chatMessageRepository,
                       CategoryRepository categoryRepository,
                       TransactionService transactionService,
                       CurrentUserService currentUser,
                       RedisRateLimitService rateLimitService,
                       @Value("${zenzmoney.chat.confidence-threshold}") double confidenceThreshold) {
        this.llmClient = llmClient;
        this.adviceClient = adviceClient;
        this.intentResolver = intentResolver;
        this.suggestions = suggestions;
        this.snapshotService = snapshotService;
        this.chatMessageRepository = chatMessageRepository;
        this.categoryRepository = categoryRepository;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
        this.rateLimitService = rateLimitService;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Reads one message — on its own, or as the answer to the question the last turn
     * asked — and returns a draft. No ledger write happens here.
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> The model call takes
     * seconds; holding a pooled DB connection across it is how the pool starves
     * under load. The two transcript rows are therefore written after the slow
     * work, in their own short transactions — a torn write leaves an orphan log
     * line, which is a cosmetic loss in an audit trail, not a ledger inconsistency.
     */
    public ChatReplyResponse handle(ChatRequest request) {
        User user = currentUser.requireUser();

        RateLimitResult limit = rateLimitService.tryConsumeOrDeny("chat:" + user.getId(), CHAT_POLICY);
        if (!limit.allowed()) {
            throw new TooManyRequestsException(RATE_LIMIT_CODE,
                    "Too many chat messages. Please wait a moment before trying again.",
                    limit.retryAfterSeconds());
        }

        String message = request.getMessage().trim();
        boolean continuing = request.getSessionId() != null && !request.getSessionId().isBlank();
        String sessionId = continuing ? request.getSessionId().trim() : UUID.randomUUID().toString();

        // Only the user's own category names leave the app, and only to a model we
        // host (§9 privacy). Sorted so the same message always builds the same prompt.
        List<String> categoryNames = categoryRepository.findByUserId(user.getId()).stream()
                .map(Category::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        // A conversation that starts with this message has nothing open to look up.
        ChatMessage pendingTurn = continuing ? liveTurn(user.getId(), sessionId) : null;
        ParsedIntent pendingDraft = pendingTurn == null ? null : pendingTurn.getParsedIntent();
        // Only a turn that actually asked something frames the next message as an answer.
        String pendingQuestion = pendingTurn != null
                && pendingTurn.getStatus() == ChatMessageStatus.NEEDS_CLARIFICATION
                ? pendingTurn.getContent()
                : null;

        long startedAt = System.currentTimeMillis();
        LlmExtraction extraction = llmClient.extract(message, categoryNames, pendingQuestion);
        ParsedIntent draft = intentResolver.resolve(user, message, extraction, pendingDraft);

        // A question is a different job from a capture: answer it from the ledger and
        // leave any draft in progress untouched. (A failed extraction always reads
        // UNKNOWN, so reaching here means the model really did say QUERY.)
        if (draft.getIntent() == IntentType.QUERY) {
            return answerQuestion(user, sessionId, message, startedAt);
        }

        ChatMessageStatus status = statusFor(extraction, draft);
        ChatPromptView prompt = status == ChatMessageStatus.FAILED
                ? null
                : suggestions.promptFor(user.getId(), draft);

        // Shape of the outcome, not the content: message length rather than the message, so a
        // confidence or resolution regression is diagnosable without logging what the user typed.
        log.info("Chat extraction: status={} intent={} confidence={} failed={} continuing={} asking={} "
                        + "chars={} categories={} in {}ms (user {}, session {})",
                status, extraction.getIntent(), extraction.getConfidence(), extraction.isFailed(),
                pendingDraft != null, prompt == null ? "nothing" : prompt.getField(),
                message.length(), categoryNames.size(),
                System.currentTimeMillis() - startedAt, user.getId(), sessionId);
        record(user, sessionId, ChatRole.USER, message, null, ChatMessageStatus.RECEIVED);
        ChatMessage assistantTurn =
                record(user, sessionId, ChatRole.ASSISTANT, replyFor(draft, status, prompt),
                        status == ChatMessageStatus.FAILED ? null : draft, status);

        // Only once the new turn actually carries the capture forward. A model outage or a
        // change of subject must leave the old draft live, or the user loses work they can
        // neither see nor recover.
        if (status != ChatMessageStatus.FAILED && draft.getIntent() == IntentType.CREATE_TRANSACTION) {
            supersede(pendingTurn, assistantTurn.getId());
        }

        return ChatReplyResponse.of(assistantTurn, prompt);
    }

    /**
     * Applies a tapped suggestion or a preview edit to a draft that has not been
     * confirmed yet.
     *
     * <p>No model call: the user picked these values, so re-reading them as language
     * would add a second of latency and a chance to get them wrong. It is also why
     * this path is not rate-limited — it costs a row update, not inference.
     *
     * <p>The draft is amended in place rather than appended as a new turn. A chip and
     * the question it answers are one exchange, and synthesising the user's side of it
     * would mean the backend rendering an amount into text — which is the client's job
     * (§0.1), not something to work around for a transcript line.
     */
    @Transactional
    public ChatReplyResponse amendDraft(UpdateChatDraftRequest request) {
        User user = currentUser.requireUser();
        ChatMessage turn = requireOwned(request.getMessageId(), user.getId());
        if (turn.getStatus() == ChatMessageStatus.CONFIRMED) {
            throw new BadRequestException("That draft is already in your ledger and cannot be changed.");
        }
        if (!LIVE.contains(turn.getStatus())) {
            throw new BadRequestException("That draft is no longer open for changes.");
        }
        ParsedIntent draft = turn.getParsedIntent();
        if (draft == null || draft.getIntent() != IntentType.CREATE_TRANSACTION) {
            throw new BadRequestException("That message has no transaction draft to change.");
        }

        apply(user, draft, request);
        intentResolver.revalidate(draft);

        ChatMessageStatus status = draft.isComplete()
                ? ChatMessageStatus.PARSED
                : ChatMessageStatus.NEEDS_CLARIFICATION;
        ChatPromptView prompt = suggestions.promptFor(user.getId(), draft);
        turn.setParsedIntent(draft);
        turn.setStatus(status);
        turn.setContent(replyFor(draft, status, prompt));
        chatMessageRepository.save(turn);

        // The draft is what the user is about to commit, so every edit to it is a state change
        // worth a line — amounts and ids only, never the note or payee text they typed.
        log.info("Chat draft amended: status={} type={} amount={} category={} asking={} (message {}, user {})",
                status, draft.getTxnType(), draft.getAmountMinor(), draft.getCategoryId(),
                prompt == null ? "nothing" : prompt.getField(), turn.getId(), user.getId());
        return ChatReplyResponse.of(turn, prompt);
    }

    /**
     * Writes a confirmed draft to the ledger through the normal transaction path,
     * so chat-entered rows get the same validation and balance derivation as
     * manually entered ones (§1.6/§1.10). The payee name goes along as a name —
     * {@code TransactionService} resolves it to a {@code Payee} row, which is why
     * no payee exists until this moment (§5.7).
     */
    @Transactional
    public TransactionResponse confirm(String messageId) {
        ChatMessage turn = requireOwned(messageId, currentUser.requireUserId());
        if (turn.getStatus() == ChatMessageStatus.CONFIRMED) {
            throw new BadRequestException("That draft has already been added to your ledger.");
        }
        if (turn.getStatus() == ChatMessageStatus.SUPERSEDED) {
            throw new BadRequestException("That draft was replaced by a newer one.");
        }
        if (turn.getStatus() != ChatMessageStatus.PARSED) {
            throw new BadRequestException("That message has no draft to confirm.");
        }
        ParsedIntent draft = turn.getParsedIntent();
        if (draft == null || !draft.isComplete() || draft.getAmountMinor() == null) {
            throw new BadRequestException("That draft is incomplete and cannot be added.");
        }

        CreateTransactionRequest request = new CreateTransactionRequest();
        request.setType(draft.getTxnType());
        request.setCategoryId(draft.getCategoryId());
        request.setAmount(draft.getAmountMinor());
        request.setTxnDate(draft.getTxnDate());
        request.setPayeeName(draft.getPayeeName());
        request.setNote(draft.getNote());

        TransactionResponse transaction = transactionService.create(request);

        turn.setStatus(ChatMessageStatus.CONFIRMED);
        turn.setTransactionId(transaction.getId());
        chatMessageRepository.save(turn);
        // The ledger write itself is logged by TransactionService; this records that a *chat draft*
        // was what produced it, which is the link the txn line cannot carry.
        log.info("Chat draft confirmed to ledger: message {} -> txn {}", messageId, transaction.getId());
        return transaction;
    }

    /** Discards a draft. Nothing is written to the ledger; the turn is kept for history. */
    @Transactional
    public void reject(String messageId) {
        ChatMessage turn = requireOwned(messageId, currentUser.requireUserId());
        if (turn.getStatus() == ChatMessageStatus.CONFIRMED) {
            throw new BadRequestException("That draft is already in your ledger and cannot be rejected.");
        }
        if (!LIVE.contains(turn.getStatus())) {
            throw new BadRequestException("That draft is no longer open to discard.");
        }
        turn.setStatus(ChatMessageStatus.REJECTED);
        chatMessageRepository.save(turn);
        // Rejections are the signal that extraction quality is off — worth counting over time.
        log.info("Chat draft rejected: message {}", messageId);
    }

    /**
     * Replays one conversation, oldest turn first. Only the live turn carries its
     * suggestions: every earlier question has already been answered, and re-offering
     * its chips would invite the user to answer it twice.
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
                        ? ChatMessageResponse.of(t, suggestions.promptFor(userId, t.getParsedIntent()))
                        : ChatMessageResponse.of(t))
                .toList();
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
            reply = "I don't have any spending recorded yet. Add a few transactions "
                    + "and I can tell you where your money is going.";
        } else {
            RateLimitResult limit = rateLimitService.tryConsumeOrDeny(
                    "chat-insight:" + user.getId(), INSIGHT_POLICY);
            if (!limit.allowed()) {
                throw new TooManyRequestsException(RATE_LIMIT_CODE,
                        "I can only work through your figures a few times a minute. "
                                + "Please try again shortly.",
                        limit.retryAfterSeconds());
            }
            String answer = adviceClient.answer(question, snapshot);
            if (answer == null) {
                reply = "I couldn't work that out just now. Please try again in a moment.";
                status = ChatMessageStatus.FAILED;
            } else {
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
            Category category = categoryRepository.findByIdAndUserId(categoryId, userId)
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            if (draft.getTxnType() == null) {
                draft.setTxnType(category.getKind() == CategoryKind.INCOME
                        ? TransactionType.INCOME
                        : TransactionType.EXPENSE);
            }
            if (category.getKind() != kindFor(draft.getTxnType())) {
                throw new BadRequestException("Category kind must match the transaction type.");
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
        boolean stillValid = categoryRepository.findByIdAndUserId(draft.getCategoryId(), userId)
                .filter(c -> c.getKind() == kindFor(draft.getTxnType()))
                .isPresent();
        if (!stillValid) {
            draft.setCategoryId(null);
            draft.setCategoryName(null);
        }
    }

    private ChatMessageStatus statusFor(LlmExtraction extraction, ParsedIntent draft) {
        if (extraction.isFailed()) {
            return ChatMessageStatus.FAILED;
        }
        if (!draft.isComplete() || draft.getConfidence() < confidenceThreshold) {
            return ChatMessageStatus.NEEDS_CLARIFICATION;
        }
        return ChatMessageStatus.PARSED;
    }

    /**
     * One targeted sentence, never a formatted amount — the draft carries the
     * numbers and the client renders them. When there is something to ask, the
     * question is {@link ChatSuggestions}': the sentence and the chips under it have
     * to agree, so they are decided in one place.
     */
    private String replyFor(ParsedIntent draft, ChatMessageStatus status, ChatPromptView prompt) {
        if (status == ChatMessageStatus.FAILED) {
            return "I couldn't read that just now. Could you try again, "
                    + "for example \"spent 5 on lunch\"?";
        }
        if (status == ChatMessageStatus.PARSED) {
            return "Here's the draft — check it over and I'll add it to your ledger.";
        }
        if (prompt != null) {
            return prompt.getQuestion();
        }

        if (draft.getIntent() == IntentType.UPDATE_TRANSACTION) {
            return "I can't change an existing transaction from chat yet. "
                    + "You can edit it from your transactions list.";
        }
        if (draft.getIntent() != IntentType.CREATE_TRANSACTION) {
            return "I couldn't tell what you wanted to record. "
                    + "Try something like \"spent 5 on lunch\".";
        }
        if (draft.getMissingFields().contains("currency")) {
            return "I don't know which currency you use yet. "
                    + "Finish setting up your profile and I can record this.";
        }
        // A complete draft the model itself doubted: nothing is missing to ask about.
        return "I'm not confident I read that correctly. Could you say it another way?";
    }

    private ChatMessage record(User user, String sessionId, ChatRole role, String content,
                               ParsedIntent draft, ChatMessageStatus status) {
        ChatMessage turn = new ChatMessage();
        turn.setUserId(user.getId());
        turn.setSessionId(sessionId);
        turn.setRole(role);
        turn.setContent(content);
        turn.setLanguage(user.getLanguage());
        turn.setParsedIntent(draft);
        turn.setStatus(status);
        return chatMessageRepository.save(turn);
    }

    private ChatMessage requireOwned(String messageId, String userId) {
        return chatMessageRepository.findByIdAndUserId(messageId, userId)
                .orElseThrow(() -> new NotFoundException("Chat message not found"));
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
