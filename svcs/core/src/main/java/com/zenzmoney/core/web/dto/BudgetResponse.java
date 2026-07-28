package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.core.entity.Budget;
import lombok.Getter;

/**
 * A budget plus its derived usage for the current period window. {@code spent}
 * is summed from EXPENSE transactions in {@code [periodStart, periodEnd)}; it is
 * never stored (§1.7). {@code remaining} may be negative when over budget.
 */
@Getter
public class BudgetResponse {

    private final String id;
    private final String categoryId;
    private final BudgetPeriod period;
    private final long amountLimit;
    private final String currency;
    private final long startDate;
    private final boolean rollover;
    private final AccountStatus status;

    // derived (current period window)
    private final long periodStart;
    private final long periodEnd;
    private final long spent;
    private final long remaining;

    private BudgetResponse(Budget b, long spent, long periodStart, long periodEnd) {
        this.id = b.getId();
        this.categoryId = b.getCategoryId();
        this.period = b.getPeriod();
        this.amountLimit = b.getAmountLimit();
        this.currency = b.getCurrency();
        this.startDate = b.getStartDate();
        this.rollover = b.isRollover();
        this.status = b.getStatus();
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.spent = spent;
        this.remaining = b.getAmountLimit() - spent;
    }

    public static BudgetResponse of(Budget b, long spent, long periodStart, long periodEnd) {
        return new BudgetResponse(b, spent, periodStart, periodEnd);
    }
}
