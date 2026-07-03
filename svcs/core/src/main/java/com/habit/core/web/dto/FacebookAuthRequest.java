package com.habit.core.web.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FacebookAuthRequest {

    public enum FacebookAuthType { AccessToken, AuthCode }

    private String value;
    private FacebookAuthType type;
    private String redirectUri;
}
