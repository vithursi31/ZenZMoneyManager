package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.ChatMessageRepository;
import com.zenzmoney.core.service.llm.IntentResolver;
import com.zenzmoney.core.service.llm.LlmExtraction;
import com.zenzmoney.core.service.llm.LlmExtractionClient;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import com.zenzmoney.core.web.dto.ChatMessageResponse;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The capture pipeline for chat-entered transactions (chat entry plan §4/§5.5).
 *
 * <p><b>The model proposes; the user commits.</b> {@link #handle} never writes to
 * the ledger — it produces a draft and logs it. Only {@link #confirm}, an explicit
 * second call, creates a transaction. That gate is the whole safety story for AI
 * money entry (domain §3.7), which is why the confirmable state lives in the
 * persisted {@link ChatMessageStatus} rather than in anything the client sends.
 */
@Service
public class ChatService {

    private static final String RATE_LIMIT_CODE = "E1052";

    /**
     * Chat costs CPU — a self-hosted model saturates cores for seconds per call —
     * so it is throttled per user and fails <b>closed</b>: if Redis is unreachable
     * the request is denied rather than allowed to hammer the model.
     */
    private static final RateLimitPolicy CHAT_POLICY = RateLimitPolicy
            .of(10, Duration.ofMinutes(1))
            .and(100, Duration.ofHours(1))
            .and(500, Duration.ofDays(1));

    private final LlmExtractionClient llmClient;
    private final IntentResolver intentResolver;
    private final ChatMessageRepository chatMessageRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionService transactionService;
    private final CurrentUserService currentUser;
    private final RedisRateLimitService rateLimitService;
    private final double confidenceThreshold;

    public ChatService(LlmExtractionClient llmClient,
                       IntentResolver intentResolver,
                       ChatMessageRepository chatMessageRepository,
                       CategoryRepository categoryRepository,
                       TransactionService transactionService,
                       CurrentUserService currentUser,
                       RedisRateLimitService rateLimitService,
                       @Value("${zenzmoney.chat.confidence-threshold}") double confidenceThreshold) {
        this.llmClient = llmClient;
        this.intentResolver = intentResolver;
        this.chatMessageRepository = chatMessageRepository;
        this.categoryRepository = categoryRepository;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
        this.rateLimitService = rateLimitService;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Reads one message and returns a draft. No ledger write happens here.
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
        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.getSessionId().trim();

        // Only the user's own category names leave the app, and only to a model we
        // host (§9 privacy). Sorted so the same message always builds the same prompt.
        List<String> categoryNames = categoryRepository.findByUserId(user.getId()).stream()
                .map(Category::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        LlmExtraction extraction = llmClient.extract(message, categoryNames);
        ParsedIntent draft = intentResolver.resolve(user, message, extraction);

        ChatMessageStatus status = statusFor(extraction, draft);
        record(user, sessionId, ChatRole.USER, message, null, ChatMessageStatus.RECEIVED);
        ChatMessage assistantTurn =
                record(user, sessionId, ChatRole.ASSISTANT, replyFor(extraction, draft, status),
                        status == ChatMessageStatus.FAILED ? null : draft, status);

        return ChatReplyResponse.of(assistantTurn);
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
        ChatMessage turn = requireOwned(messageId);
        if (turn.getStatus() == ChatMessageStatus.CONFIRMED) {
            throw new BadRequestException("That draft has already been added to your ledger.");
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
        request.setAccountId(draft.getAccountId());
        request.setCategoryId(draft.getCategoryId());
        request.setAmount(draft.getAmountMinor());
        request.setTxnDate(draft.getTxnDate());
        request.setPayeeName(draft.getPayeeName());
        request.setNote(draft.getNote());

        TransactionResponse transaction = transactionService.create(request);

        turn.setStatus(ChatMessageStatus.CONFIRMED);
        turn.setTransactionId(transaction.getId());
        chatMessageRepository.save(turn);
        return transaction;
    }

    /** Discards a draft. Nothing is written to the ledger; the turn is kept for history. */
    @Transactional
    public void reject(String messageId) {
        ChatMessage turn = requireOwned(messageId);
        if (turn.getStatus() == ChatMessageStatus.CONFIRMED) {
            throw new BadRequestException("That draft is already in your ledger and cannot be rejected.");
        }
        turn.setStatus(ChatMessageStatus.REJECTED);
        chatMessageRepository.save(turn);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> history(String sessionId) {
        String userId = currentUser.requireUserId();
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreatedTimeAsc(userId, sessionId.trim()).stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ChatMessageResponse::of)
                .toList();
    }

    // --- internals ---

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
     * numbers and the client renders them.
     */
    private String replyFor(LlmExtraction extraction, ParsedIntent draft, ChatMessageStatus status) {
        if (status == ChatMessageStatus.FAILED) {
            return "I couldn't read that just now. Could you try again, "
                    + "for example \"spent 5 on lunch\"?";
        }
        if (status == ChatMessageStatus.PARSED) {
            return "Here's the draft — confirm it and I'll add it to your ledger.";
        }

        if (draft.getIntent() == IntentType.QUERY) {
            return "I can't answer questions about your money yet. "
                    + "Try telling me a transaction, like \"spent 5 on lunch\".";
        }
        if (draft.getIntent() == IntentType.UPDATE_TRANSACTION) {
            return "I can't change an existing transaction from chat yet. "
                    + "You can edit it from your transactions list.";
        }
        if (draft.getIntent() != IntentType.CREATE_TRANSACTION) {
            return "I couldn't tell what you wanted to record. "
                    + "Try something like \"spent 5 on lunch\".";
        }

        // Ask about one thing at a time, most blocking first.
        List<String> missing = draft.getMissingFields();
        if (missing.contains("account")) {
            return "You don't have an account yet — add one and I can record this for you.";
        }
        if (missing.contains("transfer")) {
            return "I can't record transfers from chat yet. You can add it from your accounts.";
        }
        if (missing.contains("amount")) {
            return "How much was that?";
        }
        if (missing.contains("type")) {
            return "Was that money going out, or coming in?";
        }
        if (missing.contains("category")) {
            return "Which category should that go in?";
        }
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

    private ChatMessage requireOwned(String messageId) {
        String userId = currentUser.requireUserId();
        return chatMessageRepository.findByIdAndUserId(messageId, userId)
                .orElseThrow(() -> new NotFoundException("Chat message not found"));
    }
}
