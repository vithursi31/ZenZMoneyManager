package com.zenzmoney.common.domain;

/**
 * Lifecycle of a {@code Budget} (domain §1.7). An archived budget keeps its
 * history but stops being evaluated or alerted on; a deleted one is retired from
 * every listing — the row survives, so nothing the user planned is ever erased.
 *
 * <p>Budgets previously borrowed {@code AccountStatus}; that enum went away with
 * the single-account model (§1.4), which has no lifecycle to track.
 */
public enum BudgetStatus {
    ACTIVE,
    ARCHIVED,
    DELETED
}
