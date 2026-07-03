package com.habit.core.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckInRequest {

    @NotNull
    private Long timestamp;

    private boolean completed = true;

    private Double value;

    private String note;
}
