package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.RecurringTransaction;
import lombok.Getter;

@Getter
public class RecurringResponse {

    private final String id;
    private final String categoryId;
    private final TransactionType type;
    private final long amount;
    private final String currency;
    private final RecurringCadence cadence;
    /** The next due or renewal date. */
    private final long nextRunDate;
    private final int anchorDay;
    private final Long trialEndDate;
    private final Long endDate;
    private final boolean active;
    private final String payeeId;
    private final String note;

    private RecurringResponse(RecurringTransaction r) {
        this.id = r.getId();
        this.categoryId = r.getCategoryId();
        this.type = r.getType();
        this.amount = r.getAmount();
        this.currency = r.getCurrency();
        this.cadence = r.getCadence();
        this.nextRunDate = r.getNextRunDate();
        this.anchorDay = r.getAnchorDay();
        this.trialEndDate = r.getTrialEndDate();
        this.endDate = r.getEndDate();
        this.active = r.isActive();
        this.payeeId = r.getPayeeId();
        this.note = r.getNote();
    }

    public static RecurringResponse of(RecurringTransaction r) {
        return new RecurringResponse(r);
    }
}
