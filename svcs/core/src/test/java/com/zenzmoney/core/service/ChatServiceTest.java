package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionStatus;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.entity.RecurringTransaction;
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
import com.zenzmoney.core.web.dto.ChatReplyResponse;
import com.zenzmoney.core.web.dto.ChatRequest;
import com.zenzmoney.core.web.dto.ChatResultView;
import com.zenzmoney.core.web.dto.CreateRecurringRequest;
import com.zenzmoney.core.web.dto.CreateTransactionRequest;
import com.zenzmoney.core.web.dto.RecurringCreatedResponse;
import com.zenzmoney.core.web.dto.RecurringResponse;
import com.zenzmoney.core.web.dto.TransactionResponse;
import com.zenzmoney.core.web.dto.UpdateChatDraftRequest;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
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
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What is under test is the write decision: a message the model read completely and
 * confidently must reach the ledger without a second call, and everything else must
 * not reach it at all.
 *
 * <p>The three properties that keep that safe are asserted individually — a
 * sub-threshold reading stops at a confirmable draft, a ledger refusal leaves the
 * draft rather than losing it, and every write is reversible through
 * {@link ChatService#undo}.
 *
 * <p>Two more: a message naming several amounts produces several entries, and a
 * conversation still refines exactly one draft — a follow-up reaches the resolver
 * with what the earlier turn established, and the turn it replaces stops being
 * confirmable.
 *
 * <p>{@link IntentResolver} and {@link ChatSuggestions} are real here rather than
 * mocked. Both are pure functions of a draft plus the user's categories, and a
 * stubbed {@code revalidate} would let an edit that leaves the draft incomplete pass
 * as complete — exactly the bug these tests exist to catch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    @Mock LlmExtractionClient llmClient;
    @Mock LlmAdviceClient adviceClient;
    @Mock SpendingSnapshotService snapshotService;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock TransactionService transactionService;
    @Mock RecurringTransactionService recurringService;
    @Mock CurrentUserService currentUser;
    @Mock RedisRateLimitService rateLimitService;
    @Mock ChatText chatText;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(llmClient, adviceClient, new IntentResolver(categoryRepository),
                new ChatSuggestions(), snapshotService, chatMessageRepository, categoryRepository,
                transactionRepository, transactionService, recurringService, currentUser,
                rateLimitService, chatText);

        User user = new User();
        user.setId("u1");
        user.setActiveCurrency("USD");
        user.setTimezone("UTC");
        when(currentUser.requireUser()).thenReturn(user);
        when(currentUser.requireUserId()).thenReturn("u1");
        when(rateLimitService.tryConsumeOrDeny(anyString(), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.allow());
        when(categoryRepository.findByUserIdAndStatus("u1", CategoryStatus.ACTIVE))
                .thenReturn(List.of(food(), fuel(), groceries(), subs(), salary()));
        when(categoryRepository.findByIdAndUserIdAndStatus("c-food", "u1", CategoryStatus.ACTIVE))
                .thenReturn(Optional.of(food()));
        when(categoryRepository.findByIdAndUserIdAndStatus("c-fuel", "u1", CategoryStatus.ACTIVE))
                .thenReturn(Optional.of(fuel()));
        when(categoryRepository.findByIdAndUserIdAndStatus("c-salary", "u1", CategoryStatus.ACTIVE))
                .thenReturn(Optional.of(salary()));
        when(snapshotService.snapshotFor(any())).thenReturn(snapshot(20_000L));
        when(adviceClient.answer(anyString(), any())).thenReturn("Food & Drinks is your biggest expense.");
        when(chatText.forLocale(any())).thenReturn(UnaryOperator.identity());
        when(transactionService.create(any())).thenReturn(transactionResponse("t1"));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(UUID.randomUUID().toString());
            }
            return m;
        });
        noPendingDraft();
    }

    // --- a complete, confident message is written ---

    @Test
    void writesACompleteConfidentMessageWithoutAskingFirst() {
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", "Pizza Hut", "lunch", 0.95));

        ChatReplyResponse reply = chatService.handle(request("lunch at Pizza Hut, 48.86", null));

        ArgumentCaptor<CreateTransactionRequest> written = ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService).create(written.capture());
        assertEquals(4886L, written.getValue().getAmount(), "minor units, never a float");
        assertEquals(TransactionType.EXPENSE, written.getValue().getType());
        assertEquals("c-food", written.getValue().getCategoryId());
        assertEquals("Pizza Hut", written.getValue().getPayeeName());

        assertEquals(ChatMessageStatus.CREATED, reply.getStatus());
        assertEquals("t1", reply.getResults().get(0).getTransactionId());
    }

    @Test
    void answersWithAKeyAndNeverAFormattedAmount() {
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", "Pizza Hut", "lunch", 0.95));

        ChatReplyResponse reply = chatService.handle(request("lunch at Pizza Hut, 48.86", null));

        assertEquals(Msg.CHAT_ADDED.key(), reply.getReply(),
                "the client renders the card from the draft; the backend formats no money");
    }

    // --- a message short of something is asked about, not written ---

    @Test
    void asksForTheAmountAndWritesNothing() {
        reads(item(TransactionType.EXPENSE, null, "Food & Drinks", "Pizza Hut", "lunch", 0.92));

        ChatReplyResponse reply = chatService.handle(request("I had lunch at Pizza Hut today", null));

        verify(transactionService, never()).create(any());
        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getStatus());
        assertEquals("amount", reply.getPrompt().getField());
        assertEquals(Msg.CHAT_ASK_AMOUNT_EXPENSE.key(), reply.getReply());
    }

    @Test
    void foldsTheAnswerIntoTheSameDraftAndThenWritesIt() {
        ParsedIntent open = draftMissingAmount();
        ChatMessage openTurn = assistantTurn("m-open", ChatMessageStatus.NEEDS_CLARIFICATION, open);
        pendingDraft("s1", openTurn);
        // "48.86" on its own: the model reads nothing useful, the resolver reads the amount.
        reads(LlmExtractionBatch.of(IntentType.UNKNOWN, List.of()));

        ChatReplyResponse reply = chatService.handle(request("$48.86", "s1"));

        ArgumentCaptor<CreateTransactionRequest> written = ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService).create(written.capture());
        assertEquals(4886L, written.getValue().getAmount());
        assertEquals("c-food", written.getValue().getCategoryId(), "the category came from the earlier turn");
        assertEquals(ChatMessageStatus.CREATED, reply.getStatus());
        assertEquals(ChatMessageStatus.SUPERSEDED, openTurn.getStatus(),
                "the turn it grew out of must stop being confirmable");
    }

    // --- confidence is what separates a write from a question ---

    /**
     * There is no backend confidence gate any more. A reading the model doubts is one it
     * should have declined outright — the prompt tells it to answer UNKNOWN below 0.4 —
     * and a second threshold here only ever turned a usable capture into an extra tap.
     */
    @Test
    void writesACompleteReadingEvenWhenTheModelWasNotConfident() {
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", null, "lunch", 0.4));

        ChatReplyResponse reply = chatService.handle(request("maybe lunch 48.86", null));

        verify(transactionService).create(any());
        assertEquals(ChatMessageStatus.CREATED, reply.getStatus());
    }

    @Test
    void confirmWritesADraftThatWasHeldBackForConfirmation() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.PARSED, completeDraft(0.4));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        ChatReplyResponse reply = chatService.confirm("m1");

        verify(transactionService).create(any());
        assertEquals(ChatMessageStatus.CONFIRMED, turn.getStatus());
        assertEquals("t1", turn.getTransactionId());
        assertEquals(Msg.CHAT_ADDED.key(), reply.getReply());
    }

    @Test
    void refusesToConfirmTheSameDraftTwice() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CREATED, completeDraft(0.9));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.confirm("m1"));
        verify(transactionService, never()).create(any());
    }

    // --- several entries in one message ---

    @Test
    void writesOneEntryPerAmountNamedInTheMessage() {
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of(
                item(TransactionType.EXPENSE, "28", "Food & Drinks", null, "coffee", 0.92),
                item(TransactionType.EXPENSE, "350", "Groceries", null, "groceries", 0.92),
                item(TransactionType.EXPENSE, "120", "Fuel", null, "fuel", 0.92))));

        ChatReplyResponse reply = chatService.handle(
                request("I spent $28 on coffee, $350 on groceries and $120 on fuel today", null));

        ArgumentCaptor<CreateTransactionRequest> written = ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService, times(3)).create(written.capture());
        assertEquals(List.of(2800L, 35_000L, 12_000L),
                written.getAllValues().stream().map(CreateTransactionRequest::getAmount).toList());
        assertEquals(3, reply.getResults().size(), "three bubbles, not one listing three things");
        assertTrue(reply.getResults().stream()
                        .allMatch(r -> r.getStatus() == ChatMessageStatus.CREATED),
                "every complete entry is written");
    }

    @Test
    void asksAboutOnlyTheFirstGapWhenSeveralEntriesAreShort() {
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of(
                item(TransactionType.EXPENSE, "28", "Food & Drinks", null, "coffee", 0.92),
                item(TransactionType.EXPENSE, null, "Groceries", null, "groceries", 0.92),
                item(TransactionType.EXPENSE, null, "Fuel", null, "fuel", 0.92))));

        ChatReplyResponse reply = chatService.handle(request("coffee 28, groceries and fuel too", null));

        verify(transactionService, times(1)).create(any());
        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, reply.getResults().get(1).getStatus());
        assertEquals(Msg.CHAT_REST_UNREAD.key(), reply.getResults().get(2).getReply(),
                "a conversation has one slot for an open question; the rest must be said to be unread");
    }

    // --- something that repeats ---

    @Test
    void writesARepeatingMessageAsATemplateRatherThanARow() {
        when(recurringService.create(any())).thenReturn(recurringCreated("r1", null));
        LlmExtraction netflix = item(TransactionType.EXPENSE, "15", "Subscriptions", "Netflix", "Netflix", 0.94);
        netflix.setIntent(IntentType.CREATE_RECURRING);
        netflix.setRecurring(true);
        netflix.setCadence(RecurringCadence.MONTHLY);
        reads(LlmExtractionBatch.of(IntentType.CREATE_RECURRING, List.of(netflix)));

        ChatReplyResponse reply = chatService.handle(request("Netflix 15 every month", null));

        ArgumentCaptor<CreateRecurringRequest> written = ArgumentCaptor.forClass(CreateRecurringRequest.class);
        verify(recurringService).create(written.capture());
        verify(transactionService, never()).create(any());
        assertEquals(RecurringCadence.MONTHLY, written.getValue().getCadence());
        assertEquals(1500L, written.getValue().getAmount());
        assertEquals("Netflix", written.getValue().getPayeeName());
        assertNotNull(written.getValue().getNextRunDate(), "a template has to be anchored somewhere");

        assertEquals("r1", reply.getResults().get(0).getRecurringId());
        assertEquals(Msg.CHAT_ADDED_RECURRING.key(), reply.getReply());
    }

    @Test
    void asksHowOftenWhenARepeatNamesNoFrequency() {
        LlmExtraction spotify = item(TransactionType.EXPENSE, "15", "Subscriptions", "Spotify", "Spotify", 0.9);
        spotify.setIntent(IntentType.CREATE_RECURRING);
        spotify.setRecurring(true);
        reads(LlmExtractionBatch.of(IntentType.CREATE_RECURRING, List.of(spotify)));

        ChatReplyResponse reply = chatService.handle(request("my Spotify subscription is 15", null));

        verify(recurringService, never()).create(any());
        assertEquals("cadence", reply.getPrompt().getField(),
                "monthly would be an assumption the user never stated");
    }

    @Test
    void remembersBothRowsWhenATemplatePostsItsFirstOccurrenceImmediately() {
        when(recurringService.create(any())).thenReturn(recurringCreated("r1", "t9"));
        LlmExtraction netflix = item(TransactionType.EXPENSE, "15", "Subscriptions", "Netflix", "Netflix", 0.94);
        netflix.setIntent(IntentType.CREATE_RECURRING);
        netflix.setRecurring(true);
        netflix.setCadence(RecurringCadence.MONTHLY);
        reads(LlmExtractionBatch.of(IntentType.CREATE_RECURRING, List.of(netflix)));

        ChatResultView result = chatService.handle(request("Netflix 15 every month", null)).getResults().get(0);

        assertEquals("r1", result.getRecurringId());
        assertEquals("t9", result.getTransactionId(),
                "undo has two rows to remove, so the turn has to know about both");
    }

    // --- undo is what makes writing unasked safe ---

    @Test
    void undoRetiresTheRowTheTurnWroteWithoutDestroyingIt() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CREATED, completeDraft(0.9));
        turn.setTransactionId("t1");
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        chatService.undo("m1");

        verify(transactionService).deleteIfLive("t1");
        verify(transactionService, never()).delete(anyString());
        assertEquals(ChatMessageStatus.UNDONE, turn.getStatus());
        assertEquals(Msg.CHAT_UNDONE.key(), turn.getContent());
    }

    /**
     * Chat may create a repeating payment unasked, so it must be able to stop one — and
     * only that. Deleting the template would let a misreading destroy a rule the user
     * may have come to rely on.
     */
    @Test
    void undoStopsATemplateRatherThanDeletingIt() {
        ParsedIntent draft = completeDraft(0.9);
        draft.setIntent(IntentType.CREATE_RECURRING);
        draft.setCadence(RecurringCadence.MONTHLY);
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CREATED, draft);
        turn.setTransactionId("t9");
        turn.setRecurringId("r1");
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        chatService.undo("m1");

        verify(transactionService).deleteIfLive("t9");
        verify(recurringService).deactivateIfActive("r1");
        verify(recurringService, never()).delete(anyString());
        assertEquals(Msg.CHAT_UNDONE_RECURRING.key(), turn.getContent(),
                "\"stopped\" is the honest word — the template is still in their subscriptions");
    }

    /**
     * The failure the strict delete used to cause: a 404 on the occurrence meant the
     * template was never reached, so undo left the subscription still generating.
     */
    @Test
    void undoStillStopsTheTemplateWhenThePostedRowIsAlreadyGone() {
        when(transactionService.deleteIfLive("t9")).thenReturn(false);
        ParsedIntent draft = completeDraft(0.9);
        draft.setIntent(IntentType.CREATE_RECURRING);
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CREATED, draft);
        turn.setTransactionId("t9");
        turn.setRecurringId("r1");
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        chatService.undo("m1");

        verify(recurringService).deactivateIfActive("r1");
        assertEquals(ChatMessageStatus.UNDONE, turn.getStatus());
    }

    @Test
    void undoRefusesATurnThatNeverWroteAnything() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingAmount());
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.undo("m1"));
        verify(transactionService, never()).delete(anyString());
    }

    @Test
    void undoRefusesTheSameTurnTwice() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.UNDONE, completeDraft(0.9));
        turn.setTransactionId("t1");
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class, () -> chatService.undo("m1"));
        verify(transactionService, never()).delete(anyString());
    }

    @Test
    void undoCannotReachAnotherUsersTurn() {
        when(chatMessageRepository.findByIdAndUserId("m-theirs", "u1")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> chatService.undo("m-theirs"));
        verify(transactionService, never()).delete(anyString());
    }

    /**
     * Observed live: "28 on coffee, 350 on groceries and 120 on fuel" filed **all three**
     * under Groceries. The model had read them correctly — the resolver's "did the user
     * name a category outright?" step scanned the whole message for every item, and the
     * message contains the word "groceries". A shared message cannot discriminate between
     * the events it produced, so only the per-item guess may.
     */
    @Test
    void doesNotLetOneItemsWordingDecideEveryItemsCategory() {
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of(
                item(TransactionType.EXPENSE, "28", "Food & Drinks", null, "coffee", 0.92),
                item(TransactionType.EXPENSE, "350", "Groceries", null, "groceries", 0.92),
                item(TransactionType.EXPENSE, "120", "Fuel", null, "fuel", 0.92))));

        ChatReplyResponse reply = chatService.handle(
                request("I spent 28 on coffee, 350 on groceries and 120 on fuel today", null));

        assertEquals(List.of("c-food", "c-groceries", "c-fuel"),
                reply.getResults().stream().map(r -> r.getDraft().getCategoryId()).toList(),
                "each item keeps its own category, not the one the message happens to name");
    }

    // --- parts of one event vs a list of several ---

    /**
     * The case the whole rule exists for: "one ticket 50 for snacks 10" answering "how
     * much did you spend at the movie?" is ONE outing costing 110, not a 50 and a 10.
     */
    @Test
    void sumsSeveralAmountsWhenTheyAnswerAnOpenQuestion() {
        ChatMessage openTurn = assistantTurn("m-open", ChatMessageStatus.NEEDS_CLARIFICATION,
                draftMissingAmount());
        pendingDraft("s1", openTurn);
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of(
                item(TransactionType.EXPENSE, "50", null, null, "ticket", 0.9),
                item(TransactionType.EXPENSE, "10", null, null, "snacks", 0.9))));

        ChatReplyResponse reply = chatService.handle(request("one ticket 50 for snacks 10", "s1"));

        ArgumentCaptor<CreateTransactionRequest> written =
                ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService, times(1)).create(written.capture());
        assertEquals(6000L, written.getValue().getAmount(), "50 + 10, as one entry");
        assertEquals(1, reply.getResults().size(), "one outing, one bubble");
        assertEquals("ticket, snacks", written.getValue().getNote(),
                "the parts are what it was for");
    }

    /** The same shape of message with no open question is still a list of separate things. */
    @Test
    void stillSplitsSeveralAmountsInAFreshMessage() {
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of(
                item(TransactionType.EXPENSE, "500", "Food & Drinks", null, "coffee", 0.9),
                item(TransactionType.EXPENSE, "1200", "Fuel", null, "fuel", 0.9))));

        ChatReplyResponse reply = chatService.handle(request("spent 500 on coffee and 1200 on fuel", null));

        verify(transactionService, times(2)).create(any());
        assertEquals(2, reply.getResults().size(),
                "no open question, so these are two events and not two parts of one");
    }

    /** A single amount answering a question is the ordinary case and must not change. */
    @Test
    void leavesASingleAnsweredAmountAlone() {
        ChatMessage openTurn = assistantTurn("m-open", ChatMessageStatus.NEEDS_CLARIFICATION,
                draftMissingAmount());
        pendingDraft("s1", openTurn);
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of()));

        chatService.handle(request("48.86", "s1"));

        ArgumentCaptor<CreateTransactionRequest> written =
                ArgumentCaptor.forClass(CreateTransactionRequest.class);
        verify(transactionService).create(written.capture());
        assertEquals(4886L, written.getValue().getAmount());
    }

    // --- what the model is told about the conversation ---

    @Test
    void sendsTheLastTwoExchangesToTheModel() {
        pendingDraft("s1", assistantTurn("m-open", ChatMessageStatus.NEEDS_CLARIFICATION,
                draftMissingAmount()));
        when(chatMessageRepository.findTop4ByUserIdAndSessionIdOrderByCreatedTimeDesc("u1", "s1"))
                .thenReturn(List.of(
                        turn(ChatRole.ASSISTANT, Msg.CHAT_ASK_AMOUNT_EXPENSE.key()),
                        turn(ChatRole.USER, "me and my friend went to the movie yesterday"),
                        turn(ChatRole.ASSISTANT, Msg.CHAT_ADDED.key()),
                        turn(ChatRole.USER, "i spent 50 for food yesterday")));
        reads(LlmExtractionBatch.of(IntentType.CREATE_TRANSACTION, List.of()));

        chatService.handle(request("one ticket 50", "s1"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(llmClient).extract(anyString(), anyList(), sent.capture());
        String conversation = sent.getValue();
        assertTrue(conversation.startsWith("user: i spent 50 for food yesterday"),
                "oldest first, so the model reads it as a conversation — got: " + conversation);
        assertTrue(conversation.contains("assistant: " + Msg.CHAT_ASK_AMOUNT_EXPENSE.key()),
                "the question that makes the next message readable must be in there");
        assertEquals(4, conversation.lines().count());
    }

    /** A conversation that starts with this message has nothing to look up. */
    @Test
    void sendsNoConversationOnAFreshMessage() {
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", null, "lunch", 0.95));

        chatService.handle(request("lunch 48.86", null));

        verify(llmClient).extract(anyString(), anyList(), eq(null));
        verify(chatMessageRepository, never())
                .findTop4ByUserIdAndSessionIdOrderByCreatedTimeDesc(anyString(), anyString());
    }

    // --- the same thing twice ---

    /**
     * The double-entry case: "add my Netflix payment" when it is already there. Not
     * refused — asked, because recording the same coffee twice in a day is something
     * people genuinely do and only they know which it is.
     */
    @Test
    void asksBeforeWritingSomethingThatLooksAlreadyRecorded() {
        Transaction already = existing("t5", 4886L);
        already.setCategoryId("c-food");
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                "u1", TransactionStatus.ACTIVE, 4886L)).thenReturn(List.of(already));
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", "Pizza Hut", "lunch", 0.95));

        ChatReplyResponse reply = chatService.handle(request("lunch at Pizza Hut, 48.86", null));

        verify(transactionService, never()).create(any());
        assertEquals(ChatMessageStatus.PARSED, reply.getStatus());
        assertEquals(Msg.CHAT_DUPLICATE_SUSPECTED.key(), reply.getReply());
        assertTrue(reply.getDraft().isDuplicateSuspected());
    }

    @Test
    void confirmingADuplicateWritesItAnyway() {
        ParsedIntent draft = completeDraft(0.95);
        draft.setDuplicateSuspected(true);
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.PARSED, draft);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        chatService.confirm("m1");

        verify(transactionService).create(any());
        assertEquals(ChatMessageStatus.CONFIRMED, turn.getStatus());
    }

    /** A monthly subscription repeating 30 days later is not a duplicate of last month's. */
    @Test
    void doesNotFlagTheSameAmountADifferentDay() {
        Transaction lastMonth = existing("t5", 4886L);
        lastMonth.setCategoryId("c-food");
        lastMonth.setTxnDate(System.currentTimeMillis() - Duration.ofDays(30).toMillis());
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                "u1", TransactionStatus.ACTIVE, 4886L)).thenReturn(List.of(lastMonth));
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", "Pizza Hut", "lunch", 0.95));

        ChatReplyResponse reply = chatService.handle(request("lunch at Pizza Hut, 48.86", null));

        verify(transactionService).create(any());
        assertEquals(ChatMessageStatus.CREATED, reply.getStatus());
    }

    /** Same amount, different category — a 500 coffee and a 500 bus fare are two things. */
    @Test
    void doesNotFlagTheSameAmountInADifferentCategory() {
        Transaction other = existing("t5", 4886L);
        other.setCategoryId("c-fuel");
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                "u1", TransactionStatus.ACTIVE, 4886L)).thenReturn(List.of(other));
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", "Pizza Hut", "lunch", 0.95));

        ChatReplyResponse reply = chatService.handle(request("lunch at Pizza Hut, 48.86", null));

        verify(transactionService).create(any());
        assertEquals(ChatMessageStatus.CREATED, reply.getStatus());
    }

    // --- removing something already recorded ---

    @Test
    void aDeleteRequestOffersTheMatchAndRemovesNothingYet() {
        Transaction target = existing("t7", 250_000L);
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                "u1", TransactionStatus.ACTIVE, 250_000L)).thenReturn(List.of(target));
        readsDelete("2500");

        ChatReplyResponse reply = chatService.handle(request("remove the 2,500 restaurant expense", null));

        verify(transactionService, never()).deleteIfLive(anyString());
        assertEquals(ChatMessageStatus.PARSED, reply.getStatus());
        assertEquals(Msg.CHAT_DELETE_CONFIRM.key(), reply.getReply());
        assertEquals("t7", reply.getDraft().getTargetTransactionId());
    }

    @Test
    void confirmingADeleteRetiresTheTargetRow() {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.DELETE_TRANSACTION);
        draft.setTargetTransactionId("t7");
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.PARSED, draft);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));
        when(transactionService.deleteIfLive("t7")).thenReturn(true);

        ChatReplyResponse reply = chatService.confirm("m1");

        verify(transactionService).deleteIfLive("t7");
        verify(transactionService, never()).create(any());
        assertEquals(ChatMessageStatus.REMOVED, turn.getStatus());
        assertEquals(Msg.CHAT_DELETE_DONE.key(), reply.getReply());
    }

    /** Only possible because deleting is soft — the row is still there to put back. */
    @Test
    void undoingARemovalRestoresTheRow() {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.DELETE_TRANSACTION);
        draft.setTargetTransactionId("t7");
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.REMOVED, draft);
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));
        when(transactionService.restoreIfDeleted("t7")).thenReturn(true);

        chatService.undo("m1");

        verify(transactionService).restoreIfDeleted("t7");
        assertEquals(ChatMessageStatus.UNDONE, turn.getStatus());
        assertEquals(Msg.CHAT_RESTORED.key(), turn.getContent());
    }

    @Test
    void aDeleteRequestMatchingNothingRemovesNothingAndSaysSo() {
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                anyString(), any(), anyLong())).thenReturn(List.of());
        readsDelete("2500");

        ChatReplyResponse reply = chatService.handle(request("remove the 2,500 restaurant expense", null));

        verify(transactionService, never()).deleteIfLive(anyString());
        assertEquals(Msg.CHAT_DELETE_NOT_FOUND.key(), reply.getReply());
    }

    /** Picking one of several on the model's word would put someone's other row behind a Yes. */
    @Test
    void aDeleteRequestMatchingSeveralRefusesToChoose() {
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                "u1", TransactionStatus.ACTIVE, 250_000L))
                .thenReturn(List.of(existing("t7", 250_000L), existing("t8", 250_000L)));
        readsDelete("2500");

        ChatReplyResponse reply = chatService.handle(request("remove the 2,500 expense", null));

        verify(transactionService, never()).deleteIfLive(anyString());
        assertEquals(Msg.CHAT_DELETE_MANY.key(), reply.getReply());
    }

    /** A row old enough to be outside the look-back is not what "remove the 2,500" means. */
    @Test
    void aDeleteRequestIgnoresARowOlderThanTheLookback() {
        Transaction ancient = existing("t7", 250_000L);
        ancient.setTxnDate(System.currentTimeMillis() - Duration.ofDays(200).toMillis());
        when(transactionRepository.findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
                "u1", TransactionStatus.ACTIVE, 250_000L)).thenReturn(List.of(ancient));
        readsDelete("2500");

        ChatReplyResponse reply = chatService.handle(request("remove the 2,500 expense", null));

        assertEquals(Msg.CHAT_DELETE_NOT_FOUND.key(), reply.getReply());
    }

    /** Recurring is the one thing chat may stop but never remove. */
    @Test
    void aRequestToDeleteARecurringIsDeclinedRatherThanActedOn() {
        LlmExtraction reading = item(TransactionType.EXPENSE, null, null, "Netflix", null, 0.9);
        reading.setIntent(IntentType.DELETE_RECURRING);
        reads(LlmExtractionBatch.of(IntentType.DELETE_RECURRING, List.of(reading)));

        ChatReplyResponse reply = chatService.handle(request("cancel my Netflix subscription", null));

        verify(recurringService, never()).delete(anyString());
        verify(recurringService, never()).deactivateIfActive(anyString());
        verify(transactionService, never()).create(any());
        assertEquals(Msg.CHAT_RECURRING_DELETE_UNSUPPORTED.key(), reply.getReply());
    }

    // --- failure paths ---

    @Test
    void keepsTheDraftConfirmableWhenTheLedgerRefusesTheWrite() {
        when(transactionService.create(any())).thenThrow(new BadRequestException(Msg.CATEGORY_KIND_MISMATCH));
        reads(item(TransactionType.EXPENSE, "48.86", "Food & Drinks", null, "lunch", 0.95));

        ChatReplyResponse reply = chatService.handle(request("lunch 48.86", null));

        assertEquals(ChatMessageStatus.PARSED, reply.getStatus(),
                "a refused write must leave the draft, not lose the user's message");
        assertNull(reply.getResults().get(0).getTransactionId());
    }

    @Test
    void answersTryAgainWhenTheModelIsUnreachable() {
        reads(LlmExtractionBatch.failed());

        ChatReplyResponse reply = chatService.handle(request("lunch 48.86", null));

        verify(transactionService, never()).create(any());
        assertEquals(ChatMessageStatus.FAILED, reply.getStatus());
        assertEquals(Msg.CHAT_UNREADABLE.key(), reply.getReply());
    }

    @Test
    void leavesAnOpenDraftAloneWhenTheModelFails() {
        ChatMessage openTurn = assistantTurn("m-open", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingAmount());
        pendingDraft("s1", openTurn);
        reads(LlmExtractionBatch.failed());

        chatService.handle(request("something", "s1"));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, openTurn.getStatus(),
                "a model outage must not cost the user work they can neither see nor recover");
    }

    @Test
    void deniesTheRequestWhenTheChatBudgetIsSpent() {
        when(rateLimitService.tryConsumeOrDeny(eq("chat:u1"), any(RateLimitPolicy.class)))
                .thenReturn(RateLimitResult.deny(Duration.ofSeconds(30)));

        assertThrows(TooManyRequestsException.class, () -> chatService.handle(request("lunch 5", null)));
        verify(llmClient, never()).extract(anyString(), anyList(), any());
    }

    // --- a question is answered, not captured ---

    @Test
    void answersAQuestionFromTheLedgerAndWritesNothing() {
        reads(LlmExtractionBatch.of(IntentType.QUERY, List.of()));

        ChatReplyResponse reply = chatService.handle(request("how much did I spend on food?", null));

        verify(transactionService, never()).create(any());
        verify(recurringService, never()).create(any());
        assertEquals(ChatMessageStatus.ANSWERED, reply.getStatus());
        assertNotNull(reply.getInsight(), "the figures go back beside the prose so the reader can check it");
    }

    @Test
    void leavesAnOpenDraftAloneWhenTheUserAsksAQuestionMidCapture() {
        ChatMessage openTurn = assistantTurn("m-open", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingAmount());
        pendingDraft("s1", openTurn);
        reads(LlmExtractionBatch.of(IntentType.QUERY, List.of()));

        chatService.handle(request("actually, how much have I spent this month?", "s1"));

        assertEquals(ChatMessageStatus.NEEDS_CLARIFICATION, openTurn.getStatus());
    }

    // --- the preview edit ---

    @Test
    void amendingADraftRefinesItWithoutWriting() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.NEEDS_CLARIFICATION, draftMissingAmount());
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        ChatReplyResponse reply = chatService.amendDraft(draftRequest("m1", r -> r.setAmountMinor(4886L)));

        verify(transactionService, never()).create(any());
        assertEquals(ChatMessageStatus.PARSED, reply.getStatus(),
                "the preview ends at its own Create button, not a second write path");
        assertEquals(4886L, turn.getParsedIntent().getAmountMinor());
    }

    @Test
    void refusesToAmendADraftAlreadyWritten() {
        ChatMessage turn = assistantTurn("m1", ChatMessageStatus.CREATED, completeDraft(0.9));
        when(chatMessageRepository.findByIdAndUserId("m1", "u1")).thenReturn(Optional.of(turn));

        assertThrows(BadRequestException.class,
                () -> chatService.amendDraft(draftRequest("m1", r -> r.setAmountMinor(1L))));
    }

    // --- fixtures ---

    private void readsDelete(String amount) {
        LlmExtraction reading = item(TransactionType.EXPENSE, amount, null, null, "restaurant", 0.9);
        reading.setIntent(IntentType.DELETE_TRANSACTION);
        reads(LlmExtractionBatch.of(IntentType.DELETE_TRANSACTION, List.of(reading)));
    }

    private static ChatMessage turn(ChatRole role, String content) {
        ChatMessage m = new ChatMessage();
        m.setUserId("u1");
        m.setSessionId("s1");
        m.setRole(role);
        m.setContent(content);
        m.setStatus(role == ChatRole.USER ? ChatMessageStatus.RECEIVED : ChatMessageStatus.CREATED);
        return m;
    }

    private static Transaction existing(String id, long amountMinor) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setUserId("u1");
        t.setAccountId("a1");
        t.setType(TransactionType.EXPENSE);
        t.setCategoryId("c-food");
        t.setAmount(amountMinor);
        t.setCurrency("USD");
        t.setTxnDate(System.currentTimeMillis());
        return t;
    }

    private void reads(LlmExtraction single) {
        reads(LlmExtractionBatch.of(single.getIntent(), List.of(single)));
    }

    private void reads(LlmExtractionBatch batch) {
        when(llmClient.extract(anyString(), anyList(), any())).thenReturn(batch);
    }

    private static LlmExtraction item(TransactionType type, String amount, String category,
                                      String payee, String note, double confidence) {
        LlmExtraction e = new LlmExtraction();
        e.setIntent(IntentType.CREATE_TRANSACTION);
        e.setTxnType(type);
        e.setAmountRaw(amount);
        e.setCategoryGuess(category);
        e.setDateExpr("today");
        e.setPayee(payee);
        e.setNote(note);
        e.setConfidence(confidence);
        return e;
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

    /** "I had lunch at Pizza Hut" — everything but the amount. */
    private static ParsedIntent draftMissingAmount() {
        ParsedIntent draft = completeDraft(0.9);
        draft.setAmountMinor(null);
        draft.setPayeeName("Pizza Hut");
        draft.setNote("lunch");
        draft.getMissingFields().add("amount");
        return draft;
    }

    private static ChatMessage assistantTurn(String id, ChatMessageStatus status, ParsedIntent draft) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setUserId("u1");
        m.setRole(ChatRole.ASSISTANT);
        m.setContent(Msg.CHAT_ASK_AMOUNT_EXPENSE.key());
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

    private static Category groceries() {
        return category("c-groceries", "Groceries", CategoryKind.EXPENSE, 3);
    }

    private static Category subs() {
        return category("c-subs", "Subscriptions", CategoryKind.EXPENSE, 4);
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

    private static RecurringCreatedResponse recurringCreated(String templateId, String postedId) {
        RecurringTransaction r = new RecurringTransaction();
        r.setId(templateId);
        r.setUserId("u1");
        r.setAccountId("a1");
        r.setType(TransactionType.EXPENSE);
        r.setCategoryId("c-subs");
        r.setAmount(1500L);
        r.setCadence(RecurringCadence.MONTHLY);
        r.setNextRunDate(1_800_000_000_000L);
        return new RecurringCreatedResponse(RecurringResponse.of(r, "USD"),
                postedId == null ? null : transactionResponse(postedId));
    }
}
