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
import com.zenzmoney.core.entity.Transaction;
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
import com.zenzmoney.core.web.dto.ChatOptionView;
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateChatDraftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.Collection;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write gate is the thing under test here: reading a message must never touch
 * the ledger, and only a complete, unconfirmed draft may be committed — once.
 *
 * <p>The second thing is that a conversation refines <em>one</em> draft. A follow-up
 * has to reach the resolver with what the earlier turn established, and the turn it
 * replaces has to stop being confirmable — otherwise "actually make that 30" leaves
 * a live 20 behind it.
 *
 * <p>{@link IntentResolver} and {@link ChatSuggestions} are real here rather than
 * mocked. Both are pure functions of a draft plus the user's categories, and a
 * stubbed {@code revalidate} would let an edit that leaves the draft incomplete pass
 * as complete — exactly the bug these tests exist to catch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final double THRESHOLD = 0.7;

    @Mock LlmExtractionClient llmClient;
    @Mock LlmAdviceClient adviceClient;
    @Mock SpendingSnapshotService snapshotService;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionService transactionService;
    @Mock CurrentUserService currentUser;
    @Mock RedisRateLimitService rateLimitService;

    private IntentResolver intentResolver;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        intentResolver = spy(new IntentResolver(categoryRepository));
        chatService = new ChatService(llmClient, adviceClient, intentResolver,
                new ChatSuggestions(categoryRepository), snapshotService,
                chatMessageRepository, categoryRepository, transactionService, currentUser,
                rateLimitService, THRESHOLD);

        User user = new User();
        user.setId("u1");
        user.setActiveCurrency("USD");
        when(currentUser.requireUser()).thenReturn(user);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(rateLimitService.tryConsumeOrDeny(anyString(), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.allow());
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(food(), fuel(), salary()));
        when(categoryRepository.findByIdAndUserId("c-food", "u1")).thenReturn(Optional.of(food()));
        when(categoryRepository.findByIdAndUserId("c-fuel", "u1")).thenReturn(Optional.of(fuel()));
        when(categoryRepository.findByIdAndUserId("c-salary", "u1")).thenReturn(Optional.of(salary()));
        when(categoryRepository.findByIdAndUserId(eq("c-other-user"), anyString())).thenReturn(Optional.empty());
        when(snapshotService.snapshotFor(any())).thenReturn(snapshot(20_000L));
        when(adviceClient.answer(anyString(), any())).thenReturn("Food & Drinks is your biggest expense.");
        noPendingDraft();
        // Stand in for Hibernate's id generator, which never runs against a mocked repository.
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
        readsAs(extraction(false, 0.93), completeDraft(0.93));

        ChatReplyResponse reply = chatService.handle(request("I have spent $5 for burger", null));

        assertEquals(ChatMessageStatus.PARSED, reply.getStatus());
        assertNotNull(reply.getMessageId());
        assertNotNull(reply.getDraft());
        assertEquals(500L, reply.getDraft().getAmountMinor());
        assertNull(reply.getPrompt(), "a complete draft asks for nothing");
        verify(transactionService, never()).create(any());
    }

    @Test
    void handle_logsBothTurnsWithTheDraftOnTheAssistantTurn() {
        readsAs(extraction(false, 0.93), completeDraft(0.93));

        chatService.handle(request("I have spent $5 for burger", null));

        List<ChatMessage> saved = savedTurns(2);
        ChatMessage userTurn = saved.get(0);
        assertEquals(ChatRole.USER, userTurn.getRole());
        assertEquals("I have spent $5 for burger", userTurn.getContent());
        assertEquals(ChatMessageStatus.RECEIVED, userTurn.getStatus());
        assertNull(userTurn.getParsedIntent(), "a user turn carries no draft");

        ChatMessage assistantTurn = saved.get(1);
        assertEquals(ChatRole.ASSISTANT, assistantTurn.getRole());
        assertEquals(ChatMessageStatus.PARSED, assistantTurn.getStatus());
        assertNotNull(assistantTurn.getParsedIntent());
        assertEquals(userTurn.getSessionId(), assistantTurn.getSessionId(), "both turns share a session");
    }

    @Test
    void handle_startsASessionWhenNoneIsGivenAndReusesOneWhenItIs() {
        readsAs(extraction(false, 0.93), completeDraft(0.93));

        assertNotNull(chatService.handle(request("spent 5 on lunch", null)).getSessionId());
        assertEquals("session-42", chatService.handle(request("spent 5 on lunch", "session-42")).getSessionId());
    }

    @Test
    void handle_reportsFailedAndStoresNoDraftWhenTheModelIsDown() {
        readsAs(LlmExtraction.failed(), new ParsedIntent());

        ChatReplyResponse reply = chatService.handle(request("spent 5 on lunch", null));

        assertEquals(ChatMessageStatus.FAILED, reply.getStatus());
        assertNull(reply.getDraft(), "a failed reading must not present a draft the user could confirm");
        assertTrue(reply.getReply().toLowerCase().contains("couldn't read"));
    }

    @Test
    void handle_asksForClarificationBelowTheConfidenceThreshold() {
        readsAs(extraction(false, 0.4), completeDraft(0.4));

        ChatReplyResponse reply = chatService.handle(request("maybe five for something", null));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getStatus());
    }

    @Test
    void handle_deniesAndSkipsTheModelWhenRateLimited() {
        when(rateLimitService.tryConsumeOrDeny(anyString(), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.deny(Duration.ofSeconds(30)));

        assertThrows(TooManyRequestsException.class, () -> chatService.handle(request("spent 5", null)));

        verify(llmClient, never()).extract(anyString(), anyList(), any());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void handle_sendsOnlyTheUsersOwnCategoryNamesToTheModel() {
        readsAs(extraction(false, 0.93), completeDraft(0.93));

        chatService.handle(request("spent 5 on lunch", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> names = ArgumentCaptor.forClass(List.class);
        verify(llmClient).extract(eq("spent 5 on lunch"), names.capture(), any());
        assertEquals(List.of("Food & Drinks", "Fuel", "Salary"), names.getValue(),
                "sorted, so the same message always builds the same prompt");
    }

    // --- handle: asking for what is missing, with answers to tap ---

    @Test
    void handle_asksWhatTheMoneyWasForAndOffersTheUsersOwnExpenseCategories() {
        readsAs(extraction(false, 0.9), draftMissingCategory());

        ChatReplyResponse reply = chatService.handle(request("I spent $20", null));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getStatus());
        assertEquals("What did you spend it on?", reply.getReply());
        assertEquals("category", reply.getPrompt().getField());
        assertEquals(reply.getReply(), reply.getPrompt().getQuestion(),
                "the sentence and the chips under it must be the same question");
        assertEquals(List.of("Food & Drinks", "Fuel", "Other"),
                reply.getPrompt().getOptions().stream().map(ChatOptionView::getLabel).toList(),
                "income categories are not offered for an expense");
        assertEquals(20000L, reply.getDraft().getAmountMinor(), "the amount already read is kept");
    }

    @Test
    void handle_namesTheKnownCategoryWhenAskingHowMuch() {
        readsAs(extraction(false, 0.9), draftMissingAmount());

        ChatReplyResponse reply = chatService.handle(request("I paid for food", null));

        assertEquals("How much did you spend on Food & Drinks?", reply.getReply());
        assertEquals("amount", reply.getPrompt().getField());
        assertEquals(List.of(500L, 1000L, 2000L),
                reply.getPrompt().getOptions().stream()
                        .map(ChatOptionView::getAmountMinor).filter(java.util.Objects::nonNull).toList(),
                "minor units, so the client formats them in the user's currency");
        assertTrue(reply.getPrompt().getOptions().get(3).isFreeform(), "\"Other\" is always the way out");
    }

    // --- handle: a conversation refines one draft ---

    @Test
    void handle_carriesThePendingDraftAndTheQuestionAskedIntoTheNextTurn() {
        ChatMessage pending = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        pending.setContent("What did you spend it on?");
        pendingDraft("s1", pending);
        readsAs(extraction(false, 0.4), completeDraft(0.93));

        chatService.handle(request("Food", "s1"));

        verify(llmClient).extract("Food", List.of("Food & Drinks", "Fuel", "Salary"),
                "What did you spend it on?");
        ArgumentCaptor<ParsedIntent> carried = ArgumentCaptor.forClass(ParsedIntent.class);
        verify(intentResolver).resolve(any(), eq("Food"), any(), carried.capture());
        assertEquals(20000L, carried.getValue().getAmountMinor(),
                "the answer must reach the resolver with the $20 the first message gave");
    }

    @Test
    void handle_retiresTheTurnANewerDraftReplaces() {
        ChatMessage pending = assistantTurn("m1", ChatMessageStatus.PARSED, completeDraft(0.93));
        pendingDraft("s1", pending);
        readsAs(extraction(false, 0.93), completeDraft(0.93));

        chatService.handle(request("actually make that 30", "s1"));

        assertEquals(ChatMessageStatus.SUPERSEDED, pending.getStatus(),
                "a corrected draft must not leave its pre-correction self confirmable");
    }

    @Test
    void handle_keepsThePendingDraftAliveWhenTheModelIsDown() {
        ChatMessage pending = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        pendingDraft("s1", pending);
        readsAs(LlmExtraction.failed(), new ParsedIntent());

        chatService.handle(request("Food", "s1"));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, pending.getStatus(),
                "an outage must not cost the user the capture they had in progress");
    }

    @Test
    void handle_keepsThePendingDraftAliveWhenTheUserChangesTheSubject() {
        ChatMessage pending = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        pendingDraft("s1", pending);
        asksAQuestion();

        ChatReplyResponse reply = chatService.handle(request("how much did I spend?", "s1"));

        assertEquals(ChatMessageStatus.ANSWERED, reply.getStatus());
        assertNull(reply.getPrompt());
        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, pending.getStatus(),
                "answering a question must not cost the user the capture they had in progress");
    }

    // --- handle: answering a question from the ledger (F-1.16) ---

    @Test
    void handle_answersAQuestionFromTheUsersOwnFiguresAndWritesNothing() {
        asksAQuestion();

        ChatReplyResponse reply = chatService.handle(request("how can I reduce my expenses?", null));

        assertEquals(ChatMessageStatus.ANSWERED, reply.getStatus());
        assertEquals("Food & Drinks is your biggest expense.", reply.getReply());
        assertNull(reply.getDraft(), "a question produces nothing that could be confirmed");
        assertNull(reply.getPrompt());
        assertNotNull(reply.getInsight(), "the figures come back beside the prose so it can be checked");
        assertEquals(20_000L, reply.getInsight().getMonths().get(0).getExpenses());
        verify(transactionService, never()).create(any());
    }

    @Test
    void handle_handsTheModelTheQuestionAndTheUsersAggregates() {
        asksAQuestion();

        chatService.handle(request("how can I reduce my expenses?", null));

        ArgumentCaptor<SpendingSnapshot> given = ArgumentCaptor.forClass(SpendingSnapshot.class);
        verify(adviceClient).answer(eq("how can I reduce my expenses?"), given.capture());
        assertEquals("Food & Drinks", given.getValue().getMonths().get(0).getCategories().get(0).getName(),
                "the model answers from the category breakdown, not from raw transactions");
    }

    @Test
    void handle_recordsTheAnswerAsATurnThatCarriesNoDraft() {
        asksAQuestion();

        chatService.handle(request("how can I reduce my expenses?", null));

        List<ChatMessage> saved = savedTurns(2);
        assertEquals(ChatMessageStatus.RECEIVED, saved.get(0).getStatus());
        assertEquals(ChatMessageStatus.ANSWERED, saved.get(1).getStatus());
        assertNull(saved.get(1).getParsedIntent(), "nothing on an answer is confirmable");
    }

    @Test
    void handle_answersWithoutSpendingAModelCallWhenNothingIsRecordedYet() {
        asksAQuestion();
        when(snapshotService.snapshotFor(any())).thenReturn(snapshot(0L));

        ChatReplyResponse reply = chatService.handle(request("where does my money go?", null));

        assertEquals(ChatMessageStatus.ANSWERED, reply.getStatus());
        assertTrue(reply.getReply().contains("don't have any spending recorded"));
        verify(adviceClient, never()).answer(anyString(), any());
    }

    @Test
    void handle_reportsFailedWhenTheAnsweringModelIsDown() {
        asksAQuestion();
        when(adviceClient.answer(anyString(), any())).thenReturn(null);

        ChatReplyResponse reply = chatService.handle(request("how can I reduce my expenses?", null));

        assertEquals(ChatMessageStatus.FAILED, reply.getStatus());
        assertTrue(reply.getReply().contains("couldn't work that out"));
    }

    @Test
    void handle_deniesAQuestionOnItsOwnTighterBudget() {
        asksAQuestion();
        when(rateLimitService.tryConsumeOrDeny(eq("chat-insight:u1"), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.deny(Duration.ofSeconds(20)));

        assertThrows(TooManyRequestsException.class,
                () -> chatService.handle(request("how can I reduce my expenses?", null)));

        verify(adviceClient, never()).answer(anyString(), any());
    }

    @Test
    void handle_capsAnAnswerToWhatTheTranscriptColumnHolds() {
        asksAQuestion();
        when(adviceClient.answer(anyString(), any())).thenReturn("x".repeat(3000));

        ChatReplyResponse reply = chatService.handle(request("how can I reduce my expenses?", null));

        assertEquals(2000, reply.getReply().length(),
                "chat_message.content is VARCHAR(2000); a runaway generation must not break the insert");
    }

    @Test
    void confirm_isRejectedForAnAnsweredQuestion() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.ANSWERED, null);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.confirm("m1"));

        verify(transactionService, never()).create(any());
    }

    // --- draft: tapping an answer, or editing the preview ---

    @Test
    void amendDraft_fillsTheMissingCategoryAndTurnsTheDraftConfirmable() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        ChatReplyResponse reply = chatService.amendDraft(draftRequest("m1", r -> r.setCategoryId("c-food")));

        assertEquals(ChatMessageStatus.PARSED, reply.getStatus());
        assertEquals("c-food", reply.getDraft().getCategoryId());
        assertEquals("Food & Drinks", reply.getDraft().getCategoryName(), "the preview renders the name");
        assertNull(reply.getPrompt(), "nothing left to ask");
        verify(transactionService, never()).create(any());
    }

    @Test
    void amendDraft_asksTheNextQuestionWhenTheDraftIsStillShort() {
        ParsedIntent draft = draftMissingAmount();
        draft.setCategoryId(null);
        draft.setCategoryName(null);
        draft.getMissingFields().add("category");
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draft);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        ChatReplyResponse reply = chatService.amendDraft(draftRequest("m1", r -> r.setCategoryId("c-food")));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getStatus());
        assertEquals("amount", reply.getPrompt().getField());
        assertEquals("How much did you spend on Food & Drinks?", reply.getReply());
    }

    @Test
    void amendDraft_dropsACategoryTheNewDirectionInvalidates() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.PARSED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        ChatReplyResponse reply = chatService.amendDraft(
                draftRequest("m1", r -> r.setTxnType(TransactionType.INCOME)));

        assertNull(reply.getDraft().getCategoryId(),
                "an expense category cannot survive a flip to income — the ledger would refuse it");
        assertEquals("category", reply.getPrompt().getField());
        assertEquals(List.of("Salary", "Other"),
                reply.getPrompt().getOptions().stream().map(ChatOptionView::getLabel).toList());
    }

    @Test
    void amendDraft_settlesTheDirectionFromTheCategoryWhenNothingElseHas() {
        ParsedIntent draft = draftMissingCategory();
        draft.setTxnType(null);
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draft);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        ChatReplyResponse reply = chatService.amendDraft(draftRequest("m1", r -> r.setCategoryId("c-salary")));

        assertEquals(TransactionType.INCOME, reply.getDraft().getTxnType(),
                "\"Salary\" can only mean income; asking anyway repeats a question already answered");
        assertEquals(ChatMessageStatus.PARSED, reply.getStatus());
    }

    @Test
    void amendDraft_refusesACategoryOfTheWrongKind() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class,
                () -> chatService.amendDraft(draftRequest("m1", r -> r.setCategoryId("c-salary"))));
    }

    @Test
    void amendDraft_cannotReachAnotherUsersCategory() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(NotFoundException.class,
                () -> chatService.amendDraft(draftRequest("m1", r -> r.setCategoryId("c-other-user"))));
    }

    @Test
    void amendDraft_refusesADraftAlreadyInTheLedger() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CONFIRMED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class,
                () -> chatService.amendDraft(draftRequest("m1", r -> r.setAmountMinor(3000L))));
    }

    @Test
    void amendDraft_refusesADraftAlreadyReplaced() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.SUPERSEDED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class,
                () -> chatService.amendDraft(draftRequest("m1", r -> r.setAmountMinor(3000L))));
    }

    @Test
    void amendDraft_cannotReachAnotherUsersDraft() {
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> chatService.amendDraft(draftRequest("m1", r -> r.setAmountMinor(3000L))));
    }

    // --- confirm: the only path to the ledger ---

    @Test
    void confirm_writesTheDraftThroughTheNormalTransactionPath() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.PARSED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));
        when(transactionService.create(any())).thenReturn(transactionResponse("t1"));

        TransactionResponse created = chatService.confirm("m1");

        assertEquals("t1", created.getId());
        ArgumentCaptor<CreateTransactionRequest> request = ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService).create(request.capture());
        assertEquals(TransactionType.EXPENSE, request.getValue().getType());
        assertEquals(500L, request.getValue().getAmount());
        assertEquals("c-food", request.getValue().getCategoryId());
        assertEquals("Keells", request.getValue().getPayeeName(),
                "the name goes to TransactionService, which owns payee resolution");

        assertEquals(ChatMessageStatus.CONFIRMED, turn.getStatus());
        assertEquals("t1", turn.getTransactionId());
    }

    @Test
    void confirm_isRejectedTheSecondTimeSoADraftCannotDoubleWrite() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CONFIRMED, completeDraft(0.93));
        turn.setTransactionId("t1");
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.confirm("m1"));

        verify(transactionService, never()).create(any());
    }

    @Test
    void confirm_isRejectedForADraftANewerOneReplaced() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.SUPERSEDED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.confirm("m1"));

        verify(transactionService, never()).create(any());
    }

    @Test
    void confirm_isRejectedForADraftThatStillNeedsClarification() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
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
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.PARSED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        chatService.reject("m1");

        assertEquals(ChatMessageStatus.REJECTED, turn.getStatus());
        assertNull(turn.getTransactionId());
        verify(transactionService, never()).create(any());
    }

    @Test
    void reject_refusesOnceTheDraftIsAlreadyInTheLedger() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CONFIRMED, completeDraft(0.93));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.reject("m1"));

        assertEquals(ChatMessageStatus.CONFIRMED, turn.getStatus());
    }

    // --- history ---

    @Test
    void history_offersTheChipsOnlyOnTheQuestionStillWaitingForAnAnswer() {
        ChatMessage answered = assistantTurn("m1", ChatMessageStatus.SUPERSEDED, draftMissingCategory());
        answered.setCreatedTime(1L);
        ChatMessage live = assistantTurn("m2", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingCategory());
        live.setCreatedTime(2L);
        when(chatMessageRepository.findByUserIdAndSessionIdOrderByCreatedTimeAsc("u1", "s1"))
                .thenReturn(List.of(answered, live));

        List<ChatMessageResponse> turns = chatService.history("s1");

        assertNull(turns.get(0).getPrompt(), "an answered question must not invite a second answer");
        assertEquals("category", turns.get(1).getPrompt().getField());
    }

    // --- fixtures ---

    private void readsAs(LlmExtraction extraction, ParsedIntent draft) {
        when(llmClient.extract(anyString(), anyList(), any())).thenReturn(extraction);
        doReturn(draft).when(intentResolver).resolve(any(), anyString(), any(), any());
    }

    /** The model reads the message as a question rather than a capture. */
    private void asksAQuestion() {
        ParsedIntent question = new ParsedIntent();
        question.setIntent(IntentType.QUERY);
        question.getMissingFields().add("intent");
        readsAs(extraction(IntentType.QUERY, 0.9), question);
    }

    private static SpendingSnapshot snapshot(long expenses) {
        List<SpendingSnapshot.CategorySpend> categories = expenses == 0
                ? List.of()
                : List.of(new SpendingSnapshot.CategorySpend("c-food", "Food & Drinks", expenses));
        return new SpendingSnapshot("USD", "UTC", List.of(
                new SpendingSnapshot.MonthSpend("2026-08", 0L, expenses, categories)));
    }

    private void noPendingDraft() {
        when(chatMessageRepository.findFirstByUserIdAndSessionIdAndRoleAndStatusInOrderByCreatedTimeDesc(
                anyString(), anyString(), any(ChatRole.class), any())).thenReturn(Optional.empty());
    }

    private void pendingDraft(String sessionId, ChatMessage turn) {
        when(chatMessageRepository.findFirstByUserIdAndSessionIdAndRoleAndStatusInOrderByCreatedTimeDesc(
                eq("u1"), eq(sessionId), eq(ChatRole.ASSISTANT), any(Collection.class)))
                .thenReturn(Optional.of(turn));
    }

    private List<ChatMessage> savedTurns(int expected) {
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, org.mockito.Mockito.times(expected)).save(saved.capture());
        return saved.getAllValues();
    }

    private static ChatRequest request(String message, String sessionId) {
        ChatRequest r = new ChatRequest();
        r.setMessage(message);
        r.setSessionId(sessionId);
        return r;
    }

    private static UpdateChatDraftRequest draftRequest(String messageId,
                                                       java.util.function.Consumer<UpdateChatDraftRequest> edit) {
        UpdateChatDraftRequest r = new UpdateChatDraftRequest();
        r.setMessageId(messageId);
        edit.accept(r);
        return r;
    }

    private static LlmExtraction extraction(boolean failed, double confidence) {
        LlmExtraction e = extraction(IntentType.CREATE_TRANSACTION, confidence);
        e.setFailed(failed);
        return e;
    }

    private static LlmExtraction extraction(IntentType intent, double confidence) {
        LlmExtraction e = new LlmExtraction();
        e.setIntent(intent);
        e.setTxnType(TransactionType.EXPENSE);
        e.setAmountRaw("5");
        e.setConfidence(confidence);
        return e;
    }

    private static ParsedIntent completeDraft(double confidence) {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.CREATE_TRANSACTION);
        draft.setTxnType(TransactionType.EXPENSE);
        draft.setAmountMinor(500L);
        draft.setCurrency("USD");
        draft.setCategoryId("c-food");
        draft.setCategoryName("Food & Drinks");
        draft.setTxnDate(1_800_000_000_000L);
        draft.setPayeeName("Keells");
        draft.setNote("burger");
        draft.setConfidence(confidence);
        return draft;
    }

    /** "I spent $20" — the direction and the amount are read, what it was for is not. */
    private static ParsedIntent draftMissingCategory() {
        ParsedIntent draft = completeDraft(0.9);
        draft.setAmountMinor(20_000L);
        draft.setCategoryId(null);
        draft.setCategoryName(null);
        draft.setPayeeName(null);
        draft.setNote(null);
        draft.getMissingFields().add("category");
        return draft;
    }

    /** "I paid for food" — the category is read, the amount is not. */
    private static ParsedIntent draftMissingAmount() {
        ParsedIntent draft = completeDraft(0.9);
        draft.setAmountMinor(null);
        draft.setPayeeName(null);
        draft.setNote("food");
        draft.getMissingFields().add("amount");
        return draft;
    }

    private static ChatMessage assistantTurn(String id, ChatMessageStatus status, ParsedIntent draft) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setUserId("u1");
        m.setRole(ChatRole.ASSISTANT);
        m.setContent("Here's the draft.");
        m.setStatus(status);
        m.setParsedIntent(draft);
        return m;
    }

    private static Category category(String id, String name, CategoryKind kind, int sortOrder) {
        Category c = new Category();
        c.setId(id);
        c.setUserId("u1");
        c.setName(name);
        c.setKind(kind);
        c.setSortOrder(sortOrder);
        return c;
    }

    private static Category food() {
        return category("c-food", "Food & Drinks", CategoryKind.EXPENSE, 1);
    }

    private static Category fuel() {
        return category("c-fuel", "Fuel", CategoryKind.EXPENSE, 2);
    }

    private static Category salary() {
        return category("c-salary", "Salary", CategoryKind.INCOME, 1);
    }

    private static TransactionResponse transactionResponse(String id) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(500L);
        t.setCurrency("USD");
        t.setAccountId("a1");
        t.setCategoryId("c-food");
        t.setTxnDate(1_800_000_000_000L);
        return TransactionResponse.of(t);
    }
}
