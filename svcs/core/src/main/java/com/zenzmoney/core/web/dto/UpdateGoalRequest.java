package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update — a null field means "leave unchanged". The backing account is the
 * goal's identity and is not editable here; the currency follows that account.
 */
@Getter
@Setter
public class UpdateGoalRequest {

    @Size(max = 300)
    private String name;

    @Positive
    private Long targetAmount;

    private Long targetDate;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;
}
