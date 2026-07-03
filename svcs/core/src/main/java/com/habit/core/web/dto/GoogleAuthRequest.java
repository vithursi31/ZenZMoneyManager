package com.habit.core.web.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleAuthRequest {

    public enum GoogleAuthType { AuthCode, IdToken, AccessToken }

    private String value;
    private GoogleAuthType type;
}
