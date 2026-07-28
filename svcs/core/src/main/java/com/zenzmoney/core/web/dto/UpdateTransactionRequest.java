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
 * Full replacement of a transaction (PUT semantics) — same shape as create.
 * Because changing {@code type} changes which fields are required, an edit
 * re-specifies the whole transaction rather than patching fields; affected
 * account balances are re-derived (§1.6 / §1.10).
 */
@Getter
@Setter
public class UpdateTransactionRequest {

    @NotNull
    private TransactionType type;

    @NotBlank
    private String accountId;

    private String categoryId;

    @Positive
    private long amount;

    private String transferAccountId;

    private Long txnDate;

    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;

    private List<String> tags;
}
