package com.zenzmoney.core.service.insight;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

import java.util.List;

/**
 * Everything the assistant is allowed to know about the user's money when it
 * answers a question (F-1.16, domain §3.6).
 *
 * <p><b>This is deliberately one shape for two audiences.</b> It is both the input
 * to the model and the breakdown returned to the client, so the prose and the
 * numbers a client draws cannot come from different arithmetic — the answer stays
 * checkable against the figures shown beside it.
 *
 * <p><b>Aggregates only.</b> Category names and summed amounts leave for the model;
 * notes, payees, and individual transactions never do. A note is the user's private
 * account of their own life, and nothing in "how can I reduce my spending?" needs
 * it (§9 privacy).
 *
 * <p>Amounts are minor units of {@link #currency}, like everywhere else (§0.2). The
 * major-unit text the model reads is rendered in the prompt, which is the one place
 * that formatting is not a client's job.
 */
@Getter
public class SpendingSnapshot {

    private final String currency;

    /** The zone the month boundaries were resolved in — the user's, defaulting to UTC. */
    private final String timezone;

    /** Newest first: the month in progress, then the last complete one. */
    private final List<MonthSpend> months;

    public SpendingSnapshot(String currency, String timezone, List<MonthSpend> months) {
        this.currency = currency;
        this.timezone = timezone;
        this.months = List.copyOf(months);
    }

    /** True when there is nothing to reason about — answered without spending a model call. */
    @JsonIgnore
    public boolean isEmpty() {
        return months.stream().allMatch(m -> m.getIncome() == 0 && m.getExpenses() == 0);
    }

    @Getter
    public static class MonthSpend {

        /** ISO {@code yyyy-MM}. */
        private final String month;

        private final long income;
        private final long expenses;

        /** {@code income − expenses} for this month alone, carried forward nowhere (§1.10). */
        private final long position;

        /** Expense totals per category, biggest first. Income is not broken down. */
        private final List<CategorySpend> categories;

        public MonthSpend(String month, long income, long expenses, List<CategorySpend> categories) {
            this.month = month;
            this.income = income;
            this.expenses = expenses;
            this.position = income - expenses;
            this.categories = List.copyOf(categories);
        }
    }

    @Getter
    public static class CategorySpend {

        private final String categoryId;

        /** The category's name at read time — what the model names in its answer. */
        private final String name;

        private final long amount;

        public CategorySpend(String categoryId, String name, long amount) {
            this.categoryId = categoryId;
            this.name = name;
            this.amount = amount;
        }
    }
}
