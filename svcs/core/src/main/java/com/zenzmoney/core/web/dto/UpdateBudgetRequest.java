package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateBudgetRequest {

    @Positive
    private Long amountLimit;

    private Boolean rollover;
}
