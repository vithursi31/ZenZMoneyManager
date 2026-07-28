package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.AccountType;
import com.zenzmoney.core.entity.Account;
import lombok.Getter;

@Getter
public class AccountResponse {

    private final String id;
    private final String name;
    private final AccountType type;
    private final String currency;
    private final long openingBalance;
    private final long currentBalance;
    private final String color;
    private final String icon;
    private final AccountStatus status;
    private final int sortOrder;

    private AccountResponse(Account a) {
        this.id = a.getId();
        this.name = a.getName();
        this.type = a.getType();
        this.currency = a.getCurrency();
        this.openingBalance = a.getOpeningBalance();
        this.currentBalance = a.getCurrentBalance();
        this.color = a.getColor();
        this.icon = a.getIcon();
        this.status = a.getStatus();
        this.sortOrder = a.getSortOrder();
    }

    public static AccountResponse of(Account a) {
        return new AccountResponse(a);
    }
}
