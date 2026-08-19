package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
import lombok.Getter;

/**
 * One tappable answer to the assistant's question — the shape behind a suggestion
 * chip (F-1.11). Offering the answers rather than only asking the question is what
 * keeps a capture to two taps instead of two sentences.
 *
 * <p><b>Money stays unformatted.</b> An amount option carries {@link #amountMinor}
 * and no label, because rendering "$5.00" is the client's job with the draft's
 * currency (§0.1/§0.2) — a backend that writes the label takes on locale formatting
 * for every future client.
 */
@Getter
public class ChatOptionView {

    /** Display text. Null for an amount option, where the client formats {@link #amountMinor}. */
    private final String label;

    /** What to send back: a category id, or a {@link TransactionType} name. Null for amount and freeform. */
    private final String value;

    /** Minor units of the draft's currency. Amount options only. */
    private final Long amountMinor;

    /** The "Other" option — the client opens a text input instead of sending a value. */
    private final boolean freeform;

    private ChatOptionView(String label, String value, Long amountMinor, boolean freeform) {
        this.label = label;
        this.value = value;
        this.amountMinor = amountMinor;
        this.freeform = freeform;
    }

    public static ChatOptionView category(Category category) {
        return new ChatOptionView(category.getName(), category.getId(), null, false);
    }

    public static ChatOptionView type(TransactionType type) {
        return new ChatOptionView(type == TransactionType.INCOME ? "Income" : "Expense",
                type.name(), null, false);
    }

    public static ChatOptionView amount(long amountMinor) {
        return new ChatOptionView(null, null, amountMinor, false);
    }

    /** Always last: no list of guesses covers every case, so custom input is never a dead end. */
    public static ChatOptionView other() {
        return new ChatOptionView("Other", null, null, true);
    }
}
