package com.zenzmoney.common.domain;

/**
 * The direction of a ledger row (domain §1.6). {@code amount} is always a
 * positive magnitude; this is what gives it its sign.
 *
 * <p>There is deliberately no {@code TRANSFER}: a transfer moves money between
 * two accounts and a user has exactly one (§1.4). It returns with F-F.1.
 */
public enum TransactionType {
    INCOME, EXPENSE
}
