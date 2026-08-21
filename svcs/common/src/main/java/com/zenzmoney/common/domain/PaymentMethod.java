package com.zenzmoney.common.domain;

/**
 * How the money moved (domain §1.6) — a label on the row, not a place money is
 * kept. Where it is tracked is the {@code Account} (§1.4); this only records the
 * instrument, so reports can group by it.
 *
 * <p>Nullable on both {@code Transaction} and {@code RecurringTransaction}: null
 * means the user did not say.
 */
public enum PaymentMethod {
    CASH, CARD, BANK_TRANSFER, WALLET, OTHER
}
