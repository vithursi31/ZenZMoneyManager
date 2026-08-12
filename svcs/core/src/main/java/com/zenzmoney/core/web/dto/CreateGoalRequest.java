package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Create a savings goal (§1.9, F-3.1). Currency is not sent — it is the user's
 * active currency (§0.3). There is no backing account to name: the user has one
 * account, and progress comes from recorded contributions.
 */
@Getter
@Setter
public class CreateGoalRequest {

    @NotBlank
    @Size(max = 300)
    private String name;

    /** Minor units target. */
    @Positive
    private long targetAmount;

    /** Optional soft deadline, epoch millis. */
    private Long targetDate;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;
}
