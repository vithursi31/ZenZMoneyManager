package com.zenzmoney.core.service.oauth;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FacebookAuthResp {
    private String email;
    private String firstName;
    private String lastName;
}
