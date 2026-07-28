package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Create a savings goal (§1.9). Currency is not sent — it is taken from the backing
 * account (single active currency, §0.3). {@code accountId} is the real account that
 * holds the earmarked money.
 */
@Getter
@Setter
public class CreateGoalRequest {

    @NotBlank
    private String accountId;

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
