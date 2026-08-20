package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import com.zenzmoney.core.entity.ParsedIntent;
import com.zenzmoney.core.repository.CategoryRepository;
import com.zenzmoney.core.service.llm.IntentResolver;
import com.zenzmoney.core.web.dto.ChatOptionView;
import com.zenzmoney.core.web.dto.ChatPromptView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides the one thing to ask about a half-read draft, and the answers to offer
 * for it (F-1.11).
 *
 * <p><b>One question at a time, most blocking first.</b> A draft can be short of
 * three things at once, and asking for all three is a form, not a conversation.
 * The order is amount, then direction, then category — the category list to offer
 * depends on whether money went out or came in, so it cannot be asked first.
 */
@Service
public class ChatSuggestions {

    /** Round numbers, in major units, converted against the draft's currency. */
    private static final List<String> AMOUNT_LADDER = List.of("5", "10", "20");

    /** Enough to cover the common case; the rest of the list is a tap away behind "Other". */
    private static final int MAX_CATEGORY_OPTIONS = 5;

    private final CategoryRepository categoryRepository;

    public ChatSuggestions(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * The question to put to the user, or null when the draft asks for nothing —
     * it is complete, it is not a capture, or what it lacks is not something the
     * user can answer in chat (an unset currency needs onboarding, not a chip).
     */
    public ChatPromptView promptFor(String userId, ParsedIntent draft) {
        if (draft == null
                || draft.getIntent() != IntentType.CREATE_TRANSACTION
                || draft.isComplete()) {
            return null;
        }
        List<String> missing = draft.getMissingFields();
        if (missing.contains("currency")) {
            return null;
        }
        if (missing.contains("amount")) {
            return amountPrompt(draft);
        }
        if (missing.contains("type")) {
            return typePrompt();
        }
        if (missing.contains("category")) {
            return categoryPrompt(userId, draft.getTxnType());
        }
        return null;
    }

    // --- the three questions ---

    /**
     * Names the category when one is already known, because "How much did you spend
     * on Food & Drinks?" tells the user their first message was understood — a bare
     * "How much was that?" leaves them guessing whether to repeat themselves.
     */
    private ChatPromptView amountPrompt(ParsedIntent draft) {
        boolean income = draft.getTxnType() == TransactionType.INCOME;
        String category = draft.getCategoryName();
        String question;
        if (category == null) {
            question = "How much was that?";
        } else if (income) {
            question = "How much did you receive from " + category + "?";
        } else {
            question = "How much did you spend on " + category + "?";
        }

        List<ChatOptionView> options = new ArrayList<>();
        for (String major : AMOUNT_LADDER) {
            Long minor = IntentResolver.toMinorUnits(major, draft.getCurrency());
            if (minor != null) {
                options.add(ChatOptionView.amount(minor));
            }
        }
        options.add(ChatOptionView.other());
        return new ChatPromptView("amount", question, options);
    }

    private ChatPromptView typePrompt() {
        return new ChatPromptView("type", "Was that money going out, or coming in?",
                List.of(ChatOptionView.type(TransactionType.EXPENSE),
                        ChatOptionView.type(TransactionType.INCOME)));
    }

    /**
     * Offers only categories of the matching kind, so a tap can never produce the
     * pairing {@code TransactionService} would refuse (an expense in an income
     * category). A user with none of that kind still gets the question, with "Other"
     * as the only way through.
     */
    private ChatPromptView categoryPrompt(String userId, TransactionType type) {
        boolean income = type == TransactionType.INCOME;
        CategoryKind kind = income ? CategoryKind.INCOME : CategoryKind.EXPENSE;

        List<ChatOptionView> options = new ArrayList<>(categoryRepository.findByUserIdAndStatus(userId, CategoryStatus.ACTIVE).stream()
                .filter(c -> c.getKind() == kind)
                .sorted(Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName))
                .limit(MAX_CATEGORY_OPTIONS)
                .map(ChatOptionView::category)
                .toList());
        options.add(ChatOptionView.other());

        return new ChatPromptView("category",
                income ? "Where did that money come from?" : "What did you spend it on?",
                options);
    }
}
