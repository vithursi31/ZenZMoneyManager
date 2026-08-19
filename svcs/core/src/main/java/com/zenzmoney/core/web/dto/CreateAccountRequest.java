package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateAccountRequest {

    @Size(max = 100)
    private String name;
}
