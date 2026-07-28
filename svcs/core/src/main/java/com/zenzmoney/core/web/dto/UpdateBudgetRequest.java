package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update — a null field means "leave unchanged". The budget's category
 * and period are its identity (uniqueness key, §1.7) and are not editable here;
 * change those by recreating the budget.
 */
@Getter
@Setter
public class UpdateBudgetRequest {

    @Positive
    private Long amountLimit;

    private Long startDate;

    private Boolean rollover;
}
