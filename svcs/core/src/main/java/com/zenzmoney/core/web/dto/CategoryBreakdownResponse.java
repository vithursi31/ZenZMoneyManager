package com.zenzmoney.core.web.dto;

import lombok.Getter;

import java.util.List;

/**
 * Income and expenses over a period, split by category (F-1.19). All amounts are
 * minor units in {@code currency}; the client formats them and computes any
 * percentages — a share of a total is not money and needs no server rounding.
 */
@Getter
public class CategoryBreakdownResponse {

    private final String startDate;
    private final String endDate;
    private final String timezone;

    /** The exact window summed: {@code from} inclusive, {@code to} exclusive, epoch millis. */
    private final long from;
    private final long to;

    private final String currency;

    /** The account the figures were narrowed to, or null when they span every account. */
    private final String accountId;

    private final Section income;
    private final Section expenses;

    /** {@code income.total − expenses.total}, negative when the period ran at a deficit. */
    private final long position;

    public CategoryBreakdownResponse(String startDate, String endDate, String timezone,
                                     long from, long to, String currency, String accountId,
                                     Section income, Section expenses) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.timezone = timezone;
        this.from = from;
        this.to = to;
        this.currency = currency;
        this.accountId = accountId;
        this.income = income;
        this.expenses = expenses;
        this.position = income.getTotal() - expenses.getTotal();
    }

    /** One direction: its total, and the categories that make it up, biggest first. */
    @Getter
    public static class Section {

        private final long total;
        private final List<CategoryAmount> categories;

        public Section(long total, List<CategoryAmount> categories) {
            this.total = total;
            this.categories = categories;
        }
    }

    @Getter
    public static class CategoryAmount {

        private final String categoryId;
        private final String name;

        /** Non-null for a subcategory. Categories are listed flat; roll up on this if you want a parent view. */
        private final String parentId;

        private final String color;
        private final String icon;
        private final long amount;
        private final long transactionCount;

        public CategoryAmount(String categoryId, String name, String parentId, String color,
                              String icon, long amount, long transactionCount) {
            this.categoryId = categoryId;
            this.name = name;
            this.parentId = parentId;
            this.color = color;
            this.icon = icon;
            this.amount = amount;
            this.transactionCount = transactionCount;
        }
    }
}
