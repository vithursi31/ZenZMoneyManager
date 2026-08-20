package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.BudgetPeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateBudgetRequest {

    @NotBlank
    private String accountId;

    private String categoryId;

    @NotNull
    private BudgetPeriod period;

    /** The one period this budget applies to: {@code yyyy-MM} for MONTHLY, {@code yyyy} for YEARLY. */
    @NotBlank
    private String periodKey;

    @Positive
    private long amountLimit;

    private boolean rollover;
}
