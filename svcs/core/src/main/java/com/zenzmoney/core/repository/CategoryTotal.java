package com.zenzmoney.core.repository;

import lombok.Getter;

/**
 * One row of a {@code GROUP BY category} aggregate — the category's id and its
 * summed amount in minor units.
 *
 * <p>A class with a matching constructor rather than an interface projection so the
 * JPQL is a plain constructor expression: aggregate projections are where Hibernate
 * and Spring Data disagree most, and this shape has no ambiguity to get wrong. The
 * category's <em>name</em> is not here — the aggregate runs over
 * {@code transaction} alone, and the caller already holds the user's categories.
 */
@Getter
public class CategoryTotal {

    private final String categoryId;
    private final long amount;

    public CategoryTotal(String categoryId, Long amount) {
        this.categoryId = categoryId;
        this.amount = amount == null ? 0L : amount;
    }
}
