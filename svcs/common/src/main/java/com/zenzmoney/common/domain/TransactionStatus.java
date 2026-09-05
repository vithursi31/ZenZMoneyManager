package com.zenzmoney.common.domain;

/**
 * Lifecycle of a {@link com.zenzmoney.common.domain.TransactionType transaction} row
 * (domain §1.6). Deleting is soft: the row survives and is withdrawn from every list
 * and every total, so a chat turn, a savings-goal contribution, or an audit line that
 * references it still resolves to something.
 *
 * <p><b>This is lifecycle, never settlement.</b> There is deliberately no
 * {@code PENDING}/{@code PAID} here and there must never be one: whether a payment has
 * fallen due is derivable from its date, and storing it would put an "is it real yet"
 * filter on every aggregate for no gain (§1.10). Deletion is the opposite case — it is
 * derivable from nothing, which is the only reason this column earns its cost.
 */
public enum TransactionStatus {

    /** A real row. The only status any total, list, or breakdown counts. */
    ACTIVE,

    /** Deleted by the user. Kept for referential sanity; counted by nothing. */
    DELETED
}
