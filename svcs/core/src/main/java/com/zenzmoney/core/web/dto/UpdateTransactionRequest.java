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
 * An edit re-specifies the whole transaction rather than patching fields, so the
 * type/category pairing is validated the same way on every write. Moving
 * {@code txnDate} across a month boundary simply re-slices which month the row
 * counts in; nothing is recomputed eagerly (§1.10).
 */
@Getter
@Setter
public class UpdateTransactionRequest {

    @NotNull
    private TransactionType type;

    @NotBlank
    private String categoryId;

    @Positive
    private long amount;

    private Long txnDate;

    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;

    private List<String> tags;
}
