package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Transaction;
import lombok.Getter;

import java.util.List;

/** A ledger row as the client sees it. */
@Getter
public class TransactionResponse {

    private final String id;
    private final String accountId;
    private final TransactionType type;
    private final String categoryId;
    private final long amount;
    private final String currency;
    private final long txnDate;
    private final String payeeId;
    private final String note;
    private final PaymentMethod paymentMethod;
    private final List<String> tags;
    private final String recurringId;

    private TransactionResponse(Transaction t) {
        this.id = t.getId();
        this.accountId = t.getAccountId();
        this.type = t.getType();
        this.categoryId = t.getCategoryId();
        this.amount = t.getAmount();
        this.currency = t.getCurrency();
        this.txnDate = t.getTxnDate();
        this.payeeId = t.getPayeeId();
        this.note = t.getNote();
        this.paymentMethod = t.getPaymentMethod();
        this.tags = t.getTags();
        this.recurringId = t.getRecurringId();
    }

    public static TransactionResponse of(Transaction t) {
        return new TransactionResponse(t);
    }
}
