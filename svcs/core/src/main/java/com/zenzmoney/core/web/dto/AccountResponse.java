package com.zenzmoney.core.web.dto;

import com.zenzmoney.core.entity.Account;
import lombok.Getter;

/**
 * The caller's single account (§1.4). Carries no balance — the figure the UI shows
 * is the monthly position from {@code /api/v1/summary/monthly} (§1.10).
 */
@Getter
public class AccountResponse {

    private final String id;
    private final String currency;
    private final Long createdTime;

    private AccountResponse(Account a) {
        this.id = a.getId();
        this.currency = a.getCurrency();
        this.createdTime = a.getCreatedTime();
    }

    public static AccountResponse of(Account a) {
        return new AccountResponse(a);
    }
}
