package com.zenzmoney.core.web.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppleAuthRequest {
    private String authorizationCode;
    private String identityToken;
    private String email;
    private String givenName;
    private String familyName;
    private String nonce;
    private boolean isMobileApp;
}
