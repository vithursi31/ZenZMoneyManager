package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Record a funding event toward a goal (§1.9). Optionally links the real
 * {@code transactionId} that moved the money into the backing account; when set,
 * its amount and currency must match. A null transaction is a manual adjustment.
 */
@Getter
@Setter
public class CreateContributionRequest {

    /** Minor units, positive. */
    @Positive
    private long amount;

    /** Optional FK → the transaction that funded this contribution. */
    private String transactionId;

    /** Epoch millis; defaults to now when omitted (null/0). */
    private Long contributedAt;

    @Size(max = 500)
    private String note;
}
