package com.zenzmoney.core.web.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {
    private String email;
    private String password;

    /**
     * BCP-47 locale the client is running in, e.g. {@code si-LK}. Optional hint only:
     * it seeds a provisional currency so a user who skips onboarding still has one
     * (F-1.27). Unparseable values are ignored rather than rejected.
     */
    private String locale;

    /** IANA zone the client is running in, e.g. {@code Asia/Colombo}. Optional hint; sets where months begin (§1.10). */
    private String timezone;
}
