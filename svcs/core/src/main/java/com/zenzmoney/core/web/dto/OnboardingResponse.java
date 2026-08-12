package com.zenzmoney.core.web.dto;

import lombok.Getter;

/** What onboarding provisioned (F-1.27). */
@Getter
public class OnboardingResponse {

    /** The user's single account, created here if it did not already exist. */
    private final String accountId;

    private final String currency;
    private final String language;
    private final String timezone;

    /** Total categories the user now has — the seeded set, or their existing ones. */
    private final int categoryCount;

    public OnboardingResponse(String accountId, String currency, String language,
                              String timezone, int categoryCount) {
        this.accountId = accountId;
        this.currency = currency;
        this.language = language;
        this.timezone = timezone;
        this.categoryCount = categoryCount;
    }
}
