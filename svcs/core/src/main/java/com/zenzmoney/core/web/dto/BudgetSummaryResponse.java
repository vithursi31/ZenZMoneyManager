package com.zenzmoney.core.web.dto;

import lombok.Getter;

import java.util.List;

/**
 * One month's budget plan against what actually happened (F-1.6). All amounts are
 * minor units; the client formats them.
 *
 * <p>The totals cover <em>category</em> budgets only. An overall budget's spend
 * already contains every category's, so adding both would count the same money
 * twice — overall budgets are still listed in {@code budgets}, each with its own
 * limit and spend.
 *
 * <p>{@code monthExpenses} is the unfiltered truth for comparison: every EXPENSE
 * the user recorded that month, across all accounts, budgeted or not.
 */
@Getter
public class BudgetSummaryResponse {

    /** ISO {@code yyyy-MM} — the month these figures cover. */
    private final String month;

    /** The zone the month boundaries were resolved in — the user's, defaulting to UTC. */
    private final String timezone;

    /** Window start, epoch millis (inclusive). */
    private final long from;

    /** Window end, epoch millis (exclusive). */
    private final long to;

    private final String currency;

    /** Σ limits of the month's active category budgets — the cap the user set. */
    private final long totalLimit;

    /** Σ spend against those same budgets so far. */
    private final long totalSpent;

    /** {@code totalLimit − totalSpent}. Negative once the user is over budget. */
    private final long totalRemaining;

    /** Every EXPENSE recorded in the month, across all accounts — budgeted or not. */
    private final long monthExpenses;

    /** The month's active budgets, each with its own limit, spend and window. */
    private final List<BudgetResponse> budgets;

    public BudgetSummaryResponse(String month, String timezone, long from, long to, String currency,
                                 long totalLimit, long totalSpent, long totalRemaining,
                                 long monthExpenses, List<BudgetResponse> budgets) {
        this.month = month;
        this.timezone = timezone;
        this.from = from;
        this.to = to;
        this.currency = currency;
        this.totalLimit = totalLimit;
        this.totalSpent = totalSpent;
        this.totalRemaining = totalRemaining;
        this.monthExpenses = monthExpenses;
        this.budgets = budgets;
    }
}
