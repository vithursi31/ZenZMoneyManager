package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.core.entity.Budget;
import lombok.Getter;

@Getter
public class BudgetResponse {

    private final String id;
    private final String accountId;
    private final String categoryId;
    private final BudgetPeriod period;
    private final String periodKey;
    private final long amountLimit;
    private final String currency;
    private final boolean rollover;
    private final BudgetStatus status;

    private final long periodStart;
    private final long periodEnd;
    private final long spent;
    private final long remaining;

    private BudgetResponse(Budget b, String currency, long spent, long periodStart, long periodEnd) {
        this.id = b.getId();
        this.accountId = b.getAccountId();
        this.categoryId = b.getCategoryId();
        this.period = b.getPeriod();
        this.periodKey = b.getPeriodKey();
        this.amountLimit = b.getAmountLimit();
        this.currency = currency;
        this.rollover = b.isRollover();
        this.status = b.getStatus();
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.spent = spent;
        this.remaining = b.getAmountLimit() - spent;
    }

    public static BudgetResponse of(Budget b, String currency, long spent, long periodStart, long periodEnd) {
        return new BudgetResponse(b, currency, spent, periodStart, periodEnd);
    }
}
