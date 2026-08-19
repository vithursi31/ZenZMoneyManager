package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {

    @Size(max = 120)
    private String firstName;

    @Size(max = 120)
    private String lastName;
}
