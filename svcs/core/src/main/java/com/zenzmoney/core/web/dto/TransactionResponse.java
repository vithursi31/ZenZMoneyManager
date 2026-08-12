package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Transaction;
import lombok.Getter;

import java.util.List;

/**
 * A ledger row as the client sees it. {@code accountId} is deliberately absent —
 * the user has one account and never chooses it (§1.4), so exposing the id would
 * only invite clients to build account UI that has nothing to switch between.
 */
@Getter
public class TransactionResponse {

    private final String id;
    private final TransactionType type;
    private final String categoryId;
    private final long amount;
    private final String currency;
    private final long txnDate;
    private final String payeeId;
    private final String note;
    private final List<String> tags;
    private final String recurringId;

    private TransactionResponse(Transaction t) {
        this.id = t.getId();
        this.type = t.getType();
        this.categoryId = t.getCategoryId();
        this.amount = t.getAmount();
        this.currency = t.getCurrency();
        this.txnDate = t.getTxnDate();
        this.payeeId = t.getPayeeId();
        this.note = t.getNote();
        this.tags = t.getTags();
        this.recurringId = t.getRecurringId();
    }

    public static TransactionResponse of(Transaction t) {
        return new TransactionResponse(t);
    }
}
