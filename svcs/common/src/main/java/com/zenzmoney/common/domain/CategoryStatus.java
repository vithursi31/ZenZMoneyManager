package com.zenzmoney.common.domain;

/**
 * Lifecycle of a {@link Category} (domain §1.5). Deleting is soft: transactions
 * recorded in earlier months keep pointing at the category, and a report of those
 * months has to be able to name where the money went — so the row survives and is
 * merely withdrawn from every list the user picks from.
 */
public enum CategoryStatus {
    ACTIVE,
    DELETED
}
