package com.zenzmoney.core.web.dto;

import lombok.Getter;

/**
 * One calendar month's figures (F-1.2 / F-1.17). All amounts are minor units in
 * {@code currency}; the client formats them.
 *
 * <p>{@code position} is the number that used to be a stored balance. It is
 * {@code income − expenses} for this month alone — it does not include earlier
 * months and is not carried into the next one, so it may legitimately be negative
 * in a month where the user spent more than they earned.
 */
@Getter
public class MonthlySummaryResponse {

    /** ISO {@code yyyy-MM}. */
    private final String month;

    /** The zone the month boundaries were resolved in — the user's, defaulting to UTC. */
    private final String timezone;

    /** Window start, epoch millis (inclusive). */
    private final long from;

    /** Window end, epoch millis (exclusive). */
    private final long to;

    private final long income;
    private final long expenses;

    /** {@code income − expenses}. Negative when the month ran at a deficit. */
    private final long position;

    private final String currency;

    /** The account the figures were narrowed to, or null when they span every account. */
    private final String accountId;

    public MonthlySummaryResponse(String month, String timezone, long from, long to,
                                  long income, long expenses, long position, String currency,
                                  String accountId) {
        this.month = month;
        this.timezone = timezone;
        this.from = from;
        this.to = to;
        this.income = income;
        this.expenses = expenses;
        this.position = position;
        this.currency = currency;
        this.accountId = accountId;
    }
}
