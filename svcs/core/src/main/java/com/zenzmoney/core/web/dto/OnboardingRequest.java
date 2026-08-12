package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * First-run setup (F-1.27). Deliberately short: currency and language are the only
 * two things the user is asked for. There is no account name, no account type, and
 * no starting balance — see §1.4 and §1.10.
 */
@Getter
@Setter
public class OnboardingRequest {

    /** ISO-4217, e.g. {@code LKR}. The account and every amount are denominated in it (F-1.25). */
    @NotBlank
    @Size(min = 3, max = 3)
    private String currency;

    /** BCP-47, e.g. {@code en}, {@code ta}, {@code si} (F-1.26). Optional. */
    @Size(max = 10)
    private String language;

    /** IANA zone, e.g. {@code Asia/Colombo}. Optional; sets where the user's months begin (§1.10). */
    @Size(max = 50)
    private String timezone;
}
