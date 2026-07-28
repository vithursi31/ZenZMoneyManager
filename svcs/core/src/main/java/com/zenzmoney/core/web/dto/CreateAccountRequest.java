package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateAccountRequest {

    @NotBlank
    @Size(max = 300)
    private String name;

    @NotNull
    private AccountType type;

    /**
     * ISO-4217. Optional: when the user already has an active currency it is used
     * and this is ignored; otherwise this seeds the account currency (§0.3).
     */
    @Size(min = 3, max = 3)
    private String currency;

    /** Minor units at account creation. Defaults to 0. May be negative for a CARD. */
    private long openingBalance;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;

    private int sortOrder;
}
