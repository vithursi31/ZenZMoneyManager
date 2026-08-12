package com.zenzmoney.core.service;

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
import com.zenzmoney.core.entity.Transaction;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.repository.ChatMessageRepository;
import com.zenzmoney.core.service.llm.IntentResolver;
import com.zenzmoney.core.service.llm.LlmExtraction;
import com.zenzmoney.core.service.llm.LlmExtractionClient;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write gate is the thing under test here: reading a message must never touch
 * the ledger, and only a complete, unconfirmed draft may be committed — once.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final double THRESHOLD = 0.7;

    @Mock LlmExtractionClient llmClient;
    @Mock IntentResolver intentResolver;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionService transactionService;
    @Mock CurrentUserService currentUser;
    @Mock RedisRateLimitService rateLimitService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(llmClient, intentResolver, chatMessageRepository, categoryRepository,
                transactionService, currentUser, rateLimitService, THRESHOLD);

        User user = new User();
        user.setId("u1");
        when(currentUser.requireUser()).thenReturn(user);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(rateLimitService.tryConsumeOrDeny(anyString(), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.allow());
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(category("Food & Drinks")));
        // Stand in for @PrePersist, which never runs against a mocked repository.
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(UUID.randomUUID().toString());
            }
            return m;
        });
    }

    // --- handle: reads, never writes ---

    @Test
    void handle_returnsADraftAndWritesNothingToTheLedger() {
        when(llmClient.extract(anyString(), anyList())).thenReturn(extraction(false, 0.93));
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(completeDraft(0.93));

        ChatReplyResponse reply = chatService.handle(request("I have spent $5 for burger", null));

        assertEquals(ChatMessageStatus.PARSED, reply.getStatus());
        assertNotNull(reply.getMessageId());
        assertNotNull(reply.getDraft());
        assertEquals(500L, reply.getDraft().getAmountMinor());
        verify(transactionService, never()).create(any());
    }

    @Test
    void handle_logsBothTurnsWithTheDraftOnTheAssistantTurn() {
        when(llmClient.extract(anyString(), anyList())).thenReturn(extraction(false, 0.93));
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(completeDraft(0.93));

        chatService.handle(request("I have spent $5 for burger", null));

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, org.mockito.Mockito.times(2)).save(saved.capture());

        ChatMessage userTurn = saved.getAllValues().get(0);
        assertEquals(ChatRole.USER, userTurn.getRole());
        assertEquals("I have spent $5 for burger", userTurn.getContent());
        assertEquals(ChatMessageStatus.RECEIVED, userTurn.getStatus());
        assertNull(userTurn.getParsedIntent(), "a user turn carries no draft");

        ChatMessage assistantTurn = saved.getAllValues().get(1);
        assertEquals(ChatRole.ASSISTANT, assistantTurn.getRole());
        assertEquals(ChatMessageStatus.PARSED, assistantTurn.getStatus());
        assertNotNull(assistantTurn.getParsedIntent());
        assertEquals(userTurn.getSessionId(), assistantTurn.getSessionId(), "both turns share a session");
    }

    @Test
    void handle_startsASessionWhenNoneIsGivenAndReusesOneWhenItIs() {
        when(llmClient.extract(anyString(), anyList())).thenReturn(extraction(false, 0.93));
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(completeDraft(0.93));

        assertNotNull(chatService.handle(request("spent 5 on lunch", null)).getSessionId());
        assertEquals("session-42", chatService.handle(request("spent 5 on lunch", "session-42")).getSessionId());
    }

    @Test
    void handle_reportsFailedAndStoresNoDraftWhenTheModelIsDown() {
        when(llmClient.extract(anyString(), anyList())).thenReturn(LlmExtraction.failed());
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(new ParsedIntent());

        ChatReplyResponse reply = chatService.handle(request("spent 5 on lunch", null));

        assertEquals(ChatMessageStatus.FAILED, reply.getStatus());
        assertNull(reply.getDraft(), "a failed reading must not present a draft the user could confirm");
        assertTrue(reply.getReply().toLowerCase().contains("couldn't read"));
    }

    @Test
    void handle_asksForClarificationBelowTheConfidenceThreshold() {
        when(llmClient.extract(anyString(), anyList())).thenReturn(extraction(false, 0.4));
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(completeDraft(0.4));

        ChatReplyResponse reply = chatService.handle(request("maybe five for something", null));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getStatus());
    }

    @Test
    void handle_asksAboutTheMissingFieldRatherThanGuessing() {
        ParsedIntent draft = completeDraft(0.9);
        draft.setAmountMinor(null);
        draft.getMissingFields().add("amount");
        when(llmClient.extract(anyString(), anyList())).thenReturn(extraction(false, 0.9));
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(draft);

        ChatReplyResponse reply = chatService.handle(request("bought lunch", null));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getStatus());
        assertEquals("How much was that?", reply.getReply());
    }

    @Test
    void handle_deniesAndSkipsTheModelWhenRateLimited() {
        when(rateLimitService.tryConsumeOrDeny(anyString(), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.deny(Duration.ofSeconds(30)));

        assertThrows(TooManyRequestsException.class, () -> chatService.handle(request("spent 5", null)));

        verify(llmClient, never()).extract(anyString(), anyList());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void handle_sendsOnlyTheUsersOwnCategoryNamesToTheModel() {
        when(categoryRepository.findByUserId("u1")).thenReturn(
                List.of(category("Transport"), category("Food & Drinks")));
        when(llmClient.extract(anyString(), anyList())).thenReturn(extraction(false, 0.93));
        when(intentResolver.resolve(any(), anyString(), any())).thenReturn(completeDraft(0.93));

        chatService.handle(request("spent 5 on lunch", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> names = ArgumentCaptor.forClass(List.class);
        verify(llmClient).extract(eq("spent 5 on lunch"), names.capture());
        assertEquals(List.of("Food & Drinks", "Transport"), names.getValue(),
                "sorted, so the same message always builds the same prompt");
    }

    // --- confirm: the only path to the ledger ---

    @Test
    void confirm_writesTheDraftThroughTheNormalTransactionPath() {
        ChatMessage turn = assistantTurn(ChatMessageStatus.PARSED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));
        when(transactionService.create(any())).thenReturn(transactionResponse("t1"));

        TransactionResponse created = chatService.confirm("m1");

        assertEquals("t1", created.getId());
        ArgumentCaptor<CreateTransactionRequest> request = ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService).create(request.capture());
        assertEquals(TransactionType.EXPENSE, request.getValue().getType());
        assertEquals(500L, request.getValue().getAmount());
        assertEquals("c1", request.getValue().getCategoryId());
        assertEquals("Keells", request.getValue().getPayeeName(),
                "the name goes to TransactionService, which owns payee resolution");

        assertEquals(ChatMessageStatus.CONFIRMED, turn.getStatus());
        assertEquals("t1", turn.getTransactionId());
    }

    @Test
    void confirm_isRejectedTheSecondTimeSoADraftCannotDoubleWrite() {
        ChatMessage turn = assistantTurn(ChatMessageStatus.CONFIRMED, completeDraft(0.93));
        turn.setTransactionId("t1");
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.confirm("m1"));

        verify(transactionService, never()).create(any());
    }

    @Test
    void confirm_isRejectedForADraftThatStillNeedsClarification() {
        ParsedIntent incomplete = completeDraft(0.9);
        incomplete.getMissingFields().add("category");
        ChatMessage turn = assistantTurn(ChatMessageStatus.NEEDS_CLARIFICATION, incomplete);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.confirm("m1"));

        verify(transactionService, never()).create(any());
    }

    @Test
    void confirm_cannotReachAnotherUsersDraft() {
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> chatService.confirm("m1"));

        verify(transactionService, never()).create(any());
    }

    // --- reject ---

    @Test
    void reject_marksTheDraftDiscardedAndWritesNothing() {
        ChatMessage turn = assistantTurn(ChatMessageStatus.PARSED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        chatService.reject("m1");

        assertEquals(ChatMessageStatus.REJECTED, turn.getStatus());
        assertNull(turn.getTransactionId());
        verify(transactionService, never()).create(any());
    }

    @Test
    void reject_refusesOnceTheDraftIsAlreadyInTheLedger() {
        ChatMessage turn = assistantTurn(ChatMessageStatus.CONFIRMED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.reject("m1"));

        assertEquals(ChatMessageStatus.CONFIRMED, turn.getStatus());
    }

    // --- fixtures ---

    private static ChatRequest request(String message, String sessionId) {
        ChatRequest r = new ChatRequest();
        r.setMessage(message);
        r.setSessionId(sessionId);
        return r;
    }

    private static LlmExtraction extraction(boolean failed, double confidence) {
        LlmExtraction e = new LlmExtraction();
        e.setIntent(IntentType.CREATE_TRANSACTION);
        e.setTxnType(TransactionType.EXPENSE);
        e.setAmountRaw("5");
        e.setConfidence(confidence);
        e.setFailed(failed);
        return e;
    }

    private static ParsedIntent completeDraft(double confidence) {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.CREATE_TRANSACTION);
        draft.setTxnType(TransactionType.EXPENSE);
        draft.setAmountMinor(500L);
        draft.setCurrency("USD");
        draft.setCategoryId("c1");
        draft.setTxnDate(1_800_000_000_000L);
        draft.setPayeeName("Keells");
        draft.setNote("burger");
        draft.setConfidence(confidence);
        return draft;
    }

    private static ChatMessage assistantTurn(ChatMessageStatus status, ParsedIntent draft) {
        ChatMessage m = new ChatMessage();
        m.setId("m1");
        m.setUserId("u1");
        m.setRole(ChatRole.ASSISTANT);
        m.setContent("Here's the draft.");
        m.setStatus(status);
        m.setParsedIntent(draft);
        return m;
    }

    private static Category category(String name) {
        Category c = new Category();
        c.setName(name);
        return c;
    }

    private static TransactionResponse transactionResponse(String id) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(500L);
        t.setCurrency("USD");
        t.setAccountId("a1");
        t.setCategoryId("c1");
        t.setTxnDate(1_800_000_000_000L);
        return TransactionResponse.of(t);
    }
}
