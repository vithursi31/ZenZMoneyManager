package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Create a transaction. Currency is not sent — it is taken from the account
 * (single active currency, §0.3). Field requirements depend on {@code type}
 * (§1.6): INCOME/EXPENSE need a matching {@code categoryId} and no
 * {@code transferAccountId}; TRANSFER needs {@code transferAccountId} and no
 * category.
 */
@Getter
@Setter
public class CreateTransactionRequest {

    @NotNull
    private TransactionType type;

    @NotBlank
    private String accountId;

    /** Required for INCOME/EXPENSE (kind must match type); must be null for TRANSFER. */
    private String categoryId;

    /** Minor units, positive magnitude; direction is derived from type. */
    @Positive
    private long amount;

    /** Required for TRANSFER (destination, ≠ accountId); must be null otherwise. */
    private String transferAccountId;

    /** Epoch millis; defaults to now when omitted (null/0). */
    private Long txnDate;

    /** Optional merchant/payer name; resolved to a Payee (§1.5b). */
    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;

    private List<String> tags;
}
