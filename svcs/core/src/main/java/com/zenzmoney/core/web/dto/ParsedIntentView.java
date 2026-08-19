package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.ParsedIntent;
import lombok.Getter;

import java.util.List;

/**
 * The draft as the API exposes it. A view rather than the stored
 * {@link ParsedIntent} itself, so the persisted jsonb shape can change without
 * breaking clients — and so the wire contract is readable in one place.
 */
@Getter
public class ParsedIntentView {

    private final IntentType intent;
    private final TransactionType txnType;
    /** Minor units of {@link #currency}; the client formats it for display. */
    private final Long amountMinor;
    private final String currency;
    private final String categoryId;
    /** The resolved category's name, so a preview renders without a second lookup. */
    private final String categoryName;
    private final String categoryGuess;
    private final Long txnDate;
    private final String payeeName;
    private final String note;
    private final double confidence;
    private final List<String> missingFields;
    private final boolean complete;

    private ParsedIntentView(ParsedIntent intent) {
        this.intent = intent.getIntent();
        this.txnType = intent.getTxnType();
        this.amountMinor = intent.getAmountMinor();
        this.currency = intent.getCurrency();
        this.categoryId = intent.getCategoryId();
        this.categoryName = intent.getCategoryName();
        this.categoryGuess = intent.getCategoryGuess();
        this.txnDate = intent.getTxnDate();
        this.payeeName = intent.getPayeeName();
        this.note = intent.getNote();
        this.confidence = intent.getConfidence();
        this.missingFields = List.copyOf(intent.getMissingFields());
        this.complete = intent.isComplete();
    }

    /** Null when the turn carries no draft (a user turn, or a failed reading). */
    public static ParsedIntentView of(ParsedIntent intent) {
        return intent == null ? null : new ParsedIntentView(intent);
    }
}
