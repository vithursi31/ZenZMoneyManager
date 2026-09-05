package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleAuthRequest {

    public enum GoogleAuthType { AuthCode, IdToken, AccessToken }

    @NotBlank
    private String value;

    @NotNull
    private GoogleAuthType type;
}
