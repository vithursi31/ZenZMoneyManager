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
 * Create a recurring template (§1.8). Currency is not sent — it is taken from the
 * account (single active currency, §0.3). Field requirements mirror a transaction:
 * INCOME/EXPENSE need a matching {@code categoryId} and no {@code transferAccountId};
 * TRANSFER needs {@code transferAccountId} and no category.
 */
@Getter
@Setter
public class CreateRecurringRequest {

    @NotNull
    private TransactionType type;

    @NotBlank
    private String accountId;

    /** Required for INCOME/EXPENSE (kind must match type); must be null for TRANSFER. */
    private String categoryId;

    /** Minor units, positive magnitude. */
    @Positive
    private long amount;

    /** Required for TRANSFER (destination, ≠ accountId); must be null otherwise. */
    private String transferAccountId;

    @NotNull
    private RecurringCadence cadence;

    /** Epoch millis of the first (and user-picked anchor) run. Its day-of-month anchors MONTHLY/YEARLY cycles. */
    @NotNull
    @Positive
    private Long nextRunDate;

    /** Optional epoch millis; stop generating once the next run would pass it. */
    private Long endDate;

    /** Optional merchant/payer name; resolved to a Payee and copied onto generated rows (§1.5b). */
    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;
}
