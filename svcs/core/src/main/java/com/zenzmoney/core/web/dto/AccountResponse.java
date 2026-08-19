package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.core.entity.Account;
import lombok.Getter;

@Getter
public class AccountResponse {

    private final String id;
    private final String name;
    private final String currency;
    private final AccountStatus status;
    private final Long createdTime;

    private AccountResponse(Account a) {
        this.id = a.getId();
        this.name = a.getName();
        this.currency = a.getCurrency();
        this.status = a.getStatus();
        this.createdTime = a.getCreatedTime();
    }

    public static AccountResponse of(Account a) {
        return new AccountResponse(a);
    }
}
