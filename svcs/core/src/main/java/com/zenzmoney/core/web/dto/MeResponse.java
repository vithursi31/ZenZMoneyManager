package com.zenzmoney.core.web.dto;

import com.zenzmoney.core.entity.User;
import lombok.Getter;

/**
 * The caller's identity and preferences.
 *
 * <p>{@code onboarded} is the field a client routes on: false means the currency
 * and language below are provisional defaults seeded at signup and the onboarding
 * screen still has something to ask (F-1.27). Before it existed the only way to
 * discover that state was to call {@code /account} and read a 400 back.
 */
@Getter
public class MeResponse {

    private final String email;

    /** Always true — the endpoint is role-gated. Kept so existing clients reading it do not break. */
    private final boolean authenticated;

    private final boolean onboarded;
    private final String activeCurrency;
    private final String language;
    private final String timezone;

    private MeResponse(User u) {
        this.email = u.getEmail();
        this.authenticated = true;
        this.onboarded = u.isOnboarded();
        this.activeCurrency = u.getActiveCurrency();
        this.language = u.getLanguage();
        this.timezone = u.getTimezone();
    }

    public static MeResponse of(User u) {
        return new MeResponse(u);
    }
}
