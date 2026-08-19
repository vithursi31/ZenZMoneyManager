package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.web.dto.ChatOptionView;
import com.zenzmoney.core.web.dto.ChatPromptView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The chips are the difference between a question the user has to type an answer to
 * and one they can tap. Two properties matter: only one thing is ever asked, and
 * every offered answer is one the ledger would accept — a chip that produces a 400
 * is worse than no chip.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatSuggestionsTest {

    @Mock CategoryRepository categoryRepository;
    @InjectMocks ChatSuggestions suggestions;

    // --- nothing to ask ---

    @Test
    void asksNothingOfACompleteDraft() {
        assertNull(suggestions.promptFor("u1", draft(TransactionType.EXPENSE, 2000L, "c-food")));
    }

    @Test
    void asksNothingOfAMessageThatIsNotACapture() {
        ParsedIntent query = new ParsedIntent();
        query.setIntent(IntentType.QUERY);
        query.getMissingFields().add("intent");

        assertNull(suggestions.promptFor("u1", query));
    }

    @Test
    void asksNothingWhenTheGapIsAnUnsetCurrency() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, "c-food");
        draft.setCurrency(null);
        draft.getMissingFields().addAll(List.of("currency", "amount"));

        assertNull(suggestions.promptFor("u1", draft),
                "no chip can set a currency — that is an onboarding step, not an answer");
    }

    @Test
    void asksNothingOfANullDraft() {
        assertNull(suggestions.promptFor("u1", null));
    }

    // --- amount ---

    @Test
    void asksHowMuchWithARoundLadderInMinorUnits() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, "c-food");
        draft.setCategoryName("Food & Drinks");
        draft.getMissingFields().add("amount");

        ChatPromptView prompt = suggestions.promptFor("u1", draft);

        assertEquals("amount", prompt.getField());
        assertEquals("How much did you spend on Food & Drinks?", prompt.getQuestion());
        assertEquals(Arrays.asList(500L, 1000L, 2000L, null),
                prompt.getOptions().stream().map(ChatOptionView::getAmountMinor).toList());
        assertTrue(prompt.getOptions().stream().allMatch(o -> o.getLabel() == null || o.isFreeform()),
                "an amount carries no label — the client formats it in the user's currency");
    }

    @Test
    void asksHowMuchWithoutNamingACategoryItDoesNotHave() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, null);
        draft.getMissingFields().addAll(List.of("amount", "category"));

        assertEquals("How much was that?", suggestions.promptFor("u1", draft).getQuestion());
    }

    @Test
    void wordsTheAmountQuestionForIncomeAsMoneyComingIn() {
        ParsedIntent draft = draft(TransactionType.INCOME, null, "c-salary");
        draft.setCategoryName("Salary");
        draft.getMissingFields().add("amount");

        assertEquals("How much did you receive from Salary?",
                suggestions.promptFor("u1", draft).getQuestion());
    }

    @Test
    void scalesTheLadderToACurrencyWithNoMinorUnit() {
        ParsedIntent draft = draft(TransactionType.EXPENSE, null, "c-food");
        draft.setCurrency("JPY");
        draft.getMissingFields().add("amount");

        assertEquals(Arrays.asList(5L, 10L, 20L, null),
                suggestions.promptFor("u1", draft).getOptions().stream()
                        .map(ChatOptionView::getAmountMinor).toList(),
                "¥5 is 5 minor units, not 500");
    }

    // --- direction ---

    @Test
    void asksTheDirectionWithBothAnswersAndNoWayToInventAThird() {
        ParsedIntent draft = draft(null, 2000L, null);
        draft.getMissingFields().add("type");

        ChatPromptView prompt = suggestions.promptFor("u1", draft);

        assertEquals("type", prompt.getField());
        assertEquals("Was that money going out, or coming in?", prompt.getQuestion());
        assertEquals(List.of("Expense", "Income"),
                prompt.getOptions().stream().map(ChatOptionView::getLabel).toList());
        assertEquals(List.of("EXPENSE", "INCOME"),
                prompt.getOptions().stream().map(ChatOptionView::getValue).toList());
        assertFalse(prompt.getOptions().stream().anyMatch(ChatOptionView::isFreeform),
                "money went out or came in; \"Other\" is not a third direction");
    }

    @Test
    void asksTheDirectionBeforeTheCategoryWhenBothAreOpen() {
        ParsedIntent draft = draft(null, 2000L, null);
        draft.getMissingFields().addAll(List.of("type", "category"));

        assertEquals("type", suggestions.promptFor("u1", draft).getField(),
                "which half of the category list to offer depends on the answer");
    }

    // --- category ---

    @Test
    void offersOnlyTheUsersOwnExpenseCategoriesForAnExpense() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME, 1),
                category("c-fuel", "Fuel", CategoryKind.EXPENSE, 2),
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE, 1)));
        ParsedIntent draft = draft(TransactionType.EXPENSE, 2000L, null);
        draft.getMissingFields().add("category");

        ChatPromptView prompt = suggestions.promptFor("u1", draft);

        assertEquals("category", prompt.getField());
        assertEquals("What did you spend it on?", prompt.getQuestion());
        assertEquals(List.of("Food & Drinks", "Fuel", "Other"),
                prompt.getOptions().stream().map(ChatOptionView::getLabel).toList(),
                "an income category here would produce a write the ledger refuses");
        assertEquals("c-food", prompt.getOptions().get(0).getValue());
    }

    @Test
    void offersOnlyIncomeCategoriesForIncomeAndWordsTheQuestionForIt() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-salary", "Salary", CategoryKind.INCOME, 1),
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE, 1)));
        ParsedIntent draft = draft(TransactionType.INCOME, 50000L, null);
        draft.getMissingFields().add("category");

        ChatPromptView prompt = suggestions.promptFor("u1", draft);

        assertEquals("Where did that money come from?", prompt.getQuestion());
        assertEquals(List.of("Salary", "Other"),
                prompt.getOptions().stream().map(ChatOptionView::getLabel).toList());
    }

    @Test
    void capsTheCategoryListSoTheChipsStayScannable() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c1", "Food & Drinks", CategoryKind.EXPENSE, 1),
                category("c2", "Groceries", CategoryKind.EXPENSE, 2),
                category("c3", "Transport", CategoryKind.EXPENSE, 3),
                category("c4", "Housing", CategoryKind.EXPENSE, 4),
                category("c5", "Utilities", CategoryKind.EXPENSE, 5),
                category("c6", "Shopping", CategoryKind.EXPENSE, 6),
                category("c7", "Health", CategoryKind.EXPENSE, 7)));
        ParsedIntent draft = draft(TransactionType.EXPENSE, 2000L, null);
        draft.getMissingFields().add("category");

        ChatPromptView prompt = suggestions.promptFor("u1", draft);

        assertEquals(6, prompt.getOptions().size(), "five categories plus the way out");
        assertTrue(prompt.getOptions().get(5).isFreeform(),
                "the rest of the list has to stay reachable through \"Other\"");
    }

    @Test
    void stillAsksWhenTheUserHasNoCategoryOfThatKind() {
        when(categoryRepository.findByUserId("u1")).thenReturn(List.of(
                category("c-food", "Food & Drinks", CategoryKind.EXPENSE, 1)));
        ParsedIntent draft = draft(TransactionType.INCOME, 50000L, null);
        draft.getMissingFields().add("category");

        ChatPromptView prompt = suggestions.promptFor("u1", draft);

        assertEquals(List.of("Other"),
                prompt.getOptions().stream().map(ChatOptionView::getLabel).toList(),
                "an empty chip row would be a dead end; \"Other\" is the way through");
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

    private static Category category(String id, String name, CategoryKind kind, int sortOrder) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setKind(kind);
        c.setSortOrder(sortOrder);
        return c;
    }
}
