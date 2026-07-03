package com.habit.core.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateHabitRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 300)
    private String name;

    private String category;

    @NotBlank
    private String frequency;

    @Min(1)
    private int targetPerPeriod = 1;
}
