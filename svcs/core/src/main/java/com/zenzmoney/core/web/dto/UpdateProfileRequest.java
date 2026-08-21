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

    /**
     * BCP-47 language tag, e.g. {@code si} or {@code si-LK}. Must be one the server can answer in;
     * onboarding sets the first value and this is the only way to change it afterwards.
     */
    @Size(max = 10)
    private String language;
}
