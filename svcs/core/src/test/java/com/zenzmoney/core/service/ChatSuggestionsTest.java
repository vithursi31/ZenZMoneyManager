package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.web.dto.ChatPromptView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * One question at a time, and always the most blocking one.
 *
 * <p>The question is a message key, not a sentence — the boundary renders it — so
 * what is asserted here is which key, not which words. That is also the reason the
 * class no longer touches the repository: with the tappable options gone there is no
 * category list to build, only a sentence to choose.
 */
class ChatSuggestionsTest {

    private final ChatSuggestions suggestions = new ChatSuggestions();

    // --- nothing to ask ---

    @Test
    void asksNothingOfACompleteDraft() {
        assertNull(suggestions.promptFor(draft(TransactionType.EXPENSE, 2000L, "c-food")));
    }

    @Test
    void asksNothingOfAMessageThatIsNotACapture() {
        ParsedIntent query = new ParsedIntent();
        query.setIntent(IntentType.QUERY);
        query.getMissingFields().add("intent");

        assertNull(suggestions.promptFor(query));
    }

    @Test
    void asksNothingWhenTheGapIsAnUnsetCurrency() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, "c-food");
        draft.setCurrency(null);
        draft.getMissingFields().addAll(List.of("currency", "amount"));

        assertNull(suggestions.promptFor(draft),
                "no answer can set a currency — that is an onboarding step");
    }

    @Test
    void asksNothingOfANullDraft() {
        assertNull(suggestions.promptFor(null));
    }

    // --- amount ---

    @Test
    void acknowledgesAnExpenseBeforeAskingHowMuch() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, "c-food");
        draft.getMissingFields().add("amount");

        ChatPromptView prompt = suggestions.promptFor(draft);

        assertEquals("amount", prompt.getField());
        assertEquals(Msg.CHAT_ASK_AMOUNT_EXPENSE.key(), prompt.getQuestion(),
                "naming the direction tells the user their message was understood");
    }

    @Test
    void wordsTheAmountQuestionForIncomeAsMoneyComingIn() {
        ParsedIntent draft = draft(TransactionType.INCOME, null, "c-salary");
        draft.getMissingFields().add("amount");

        assertEquals(Msg.CHAT_ASK_AMOUNT_INCOME.key(), suggestions.promptFor(draft).getQuestion());
    }

    @Test
    void asksHowMuchNeutrallyWhenTheDirectionIsAlsoUnknown() {
        ParsedIntent draft = draft(null, null, null);
        draft.getMissingFields().addAll(List.of("amount", "type"));

        assertEquals(Msg.CHAT_ASK_AMOUNT.key(), suggestions.promptFor(draft).getQuestion(),
                "claiming it is an expense before knowing would be a guess in the copy");
    }

    // --- direction ---

    @Test
    void asksTheDirection() {
        ParsedIntent draft = draft(null, 2000L, null);
        draft.getMissingFields().add("type");

        ChatPromptView prompt = suggestions.promptFor(draft);

        assertEquals("type", prompt.getField());
        assertEquals(Msg.CHAT_ASK_TYPE.key(), prompt.getQuestion());
    }

    @Test
    void asksTheDirectionBeforeTheCategoryWhenBothAreOpen() {
        ParsedIntent draft = draft(null, 2000L, null);
        draft.getMissingFields().addAll(List.of("type", "category"));

        assertEquals("type", suggestions.promptFor(draft).getField(),
                "which half of the category list applies depends on the answer");
    }

    @Test
    void asksTheAmountBeforeTheDirection() {
        ParsedIntent draft = draft(null, null, null);
        draft.getMissingFields().addAll(List.of("amount", "type", "category"));

        assertEquals("amount", suggestions.promptFor(draft).getField());
    }

    // --- category ---

    @Test
    void asksWhatAnExpenseWasSpentOn() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, 2000L, null);
        draft.getMissingFields().add("category");

        ChatPromptView prompt = suggestions.promptFor(draft);

        assertEquals("category", prompt.getField());
        assertEquals(Msg.CHAT_ASK_CATEGORY_EXPENSE.key(), prompt.getQuestion());
    }

    @Test
    void asksWhereIncomeCameFrom() {
        ParsedIntent draft = draft(TransactionType.INCOME, 50000L, null);
        draft.getMissingFields().add("category");

        assertEquals(Msg.CHAT_ASK_CATEGORY_INCOME.key(), suggestions.promptFor(draft).getQuestion());
    }

    // --- cadence ---

    @Test
    void asksHowOftenARecurringDraftRepeats() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, 1500L, "c-subs");
        draft.setIntent(IntentType.CREATE_RECURRING);
        draft.getMissingFields().add("cadence");

        ChatPromptView prompt = suggestions.promptFor(draft);

        assertEquals("cadence", prompt.getField());
        assertEquals(Msg.CHAT_ASK_CADENCE.key(), prompt.getQuestion(),
                "\"my Spotify subscription\" names no frequency, and monthly would be an assumption");
    }

    @Test
    void asksTheCadenceLastBecauseItBlocksLeastOfAll() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, null);
        draft.setIntent(IntentType.CREATE_RECURRING);
        draft.getMissingFields().addAll(List.of("amount", "category", "cadence"));

        assertEquals("amount", suggestions.promptFor(draft).getField());
    }

    @Test
    void asksNothingOfACompleteRecurringDraft() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, 1500L, "c-subs");
        draft.setIntent(IntentType.CREATE_RECURRING);
        draft.setCadence(RecurringCadence.MONTHLY);

        assertNull(suggestions.promptFor(draft));
    }

    // --- fixtures ---

    private static ParsedIntent draft(TransactionType type, Long amountMinor, String categoryId) {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.CREATE_TRANSACTION);
        draft.setTxnType(type);
        draft.setAmountMinor(amountMinor);
        draft.setCurrency("USD");
        draft.setCategoryId(categoryId);
        draft.setConfidence(0.9);
        return draft;
    }
}
