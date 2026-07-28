package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreatePayeeRequest {

    @NotBlank
    @Size(max = 300)
    private String name;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;
}
