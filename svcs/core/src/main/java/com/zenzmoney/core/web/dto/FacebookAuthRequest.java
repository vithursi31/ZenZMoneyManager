package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FacebookAuthRequest {

    public enum FacebookAuthType { AccessToken, AuthCode }

    @NotBlank
    private String value;

    @NotNull
    private FacebookAuthType type;

    private String redirectUri;
}
