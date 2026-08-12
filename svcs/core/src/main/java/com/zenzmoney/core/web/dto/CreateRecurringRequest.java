package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Create a recurring template — income, expense, or a subscription (§1.8, F-1.7).
 * Neither currency nor account is sent: currency comes from the user's active
 * currency (§0.3) and the account is their only one (§1.4). Field requirements
 * mirror a transaction — a {@code categoryId} whose kind matches {@code type}.
 */
@Getter
@Setter
public class CreateRecurringRequest {

    @NotNull
    private TransactionType type;

    /** Required; its kind must match {@code type}. */
    @NotBlank
    private String categoryId;

    /** Minor units, positive magnitude. The subscription's cost, for a subscription. */
    @Positive
    private long amount;

    @NotNull
    private RecurringCadence cadence;

    /** Epoch millis of the first (and user-picked anchor) run. Its day-of-month anchors MONTHLY/YEARLY cycles. */
    @NotNull
    @Positive
    private Long nextRunDate;

    /** Optional epoch millis; the free-trial end date for a subscription (F-1.7). */
    private Long trialEndDate;

    /** Optional epoch millis; stop generating once the next run would pass it. */
    private Long endDate;

    /** Optional merchant/payer name; resolved to a Payee and copied onto generated rows (§1.5b). */
    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;
}
