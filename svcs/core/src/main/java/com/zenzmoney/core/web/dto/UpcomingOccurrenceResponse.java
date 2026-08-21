package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.RecurringTransaction;
import lombok.Getter;

/**
 * One projected occurrence of a recurring template (F-1.7) — a payment the user is
 * about to make, not a transaction. It has no {@code id} because no row exists: it is
 * computed from the template on every read and counts toward no total until the
 * scheduler posts it (§1.10). Act on it through {@code recurringId}.
 */
@Getter
public class UpcomingOccurrenceResponse {

    private final String recurringId;
    private final TransactionType type;
    private final String categoryId;
    private final long amount;
    private final String currency;
    private final RecurringCadence cadence;
    /** Epoch millis this occurrence falls due. */
    private final long dueDate;
    /** True once {@code dueDate} has passed: it posts to the ledger on the next generation pass. */
    private final boolean due;
    private final String payeeId;
    private final String note;
    private final PaymentMethod paymentMethod;
    private final Long trialEndDate;
    /** True when the template's free trial ends inside the requested window. */
    private final boolean trialEnding;

    private UpcomingOccurrenceResponse(RecurringTransaction r, String currency, long dueDate,
                                       boolean due, boolean trialEnding) {
        this.recurringId = r.getId();
        this.type = r.getType();
        this.categoryId = r.getCategoryId();
        this.amount = r.getAmount();
        this.currency = currency;
        this.cadence = r.getCadence();
        this.dueDate = dueDate;
        this.due = due;
        this.payeeId = r.getPayeeId();
        this.note = r.getNote();
        this.paymentMethod = r.getPaymentMethod();
        this.trialEndDate = r.getTrialEndDate();
        this.trialEnding = trialEnding;
    }

    /** {@code currency} comes from the account the template posts to (§1.4) — it is not stored here. */
    public static UpcomingOccurrenceResponse of(RecurringTransaction r, String currency, long dueDate,
                                                boolean due, boolean trialEnding) {
        return new UpcomingOccurrenceResponse(r, currency, dueDate, due, trialEnding);
    }
}
