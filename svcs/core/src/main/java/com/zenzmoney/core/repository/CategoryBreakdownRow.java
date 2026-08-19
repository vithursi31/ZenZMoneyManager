package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.TransactionType;
import lombok.Getter;

/**
 * One (category, direction) row of the breakdown aggregate (F-1.19), carrying the
 * category's display fields so a report doesn't need a second call to label itself.
 *
 * <p>Same shape as {@link CategoryTotal} and for the same reason — a class with a
 * matching constructor, so the JPQL stays an unambiguous constructor expression.
 */
@Getter
public class CategoryBreakdownRow {

    private final String categoryId;
    private final String name;
    private final String parentId;
    private final String color;
    private final String icon;
    private final TransactionType type;
    private final long amount;
    private final long transactionCount;

    public CategoryBreakdownRow(String categoryId, String name, String parentId, String color,
                                String icon, TransactionType type, Long amount, Long transactionCount) {
        this.categoryId = categoryId;
        this.name = name;
        this.parentId = parentId;
        this.color = color;
        this.icon = icon;
        this.type = type;
        this.amount = amount == null ? 0L : amount;
        this.transactionCount = transactionCount == null ? 0L : transactionCount;
    }
}
