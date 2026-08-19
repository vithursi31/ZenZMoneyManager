package com.zenzmoney.core.web.dto;

import lombok.Getter;

/**
 * One selectable ISO-4217 currency (F-1.27). Carries {@code fractionDigits} because
 * not every currency has 2 decimal places — JPY has 0, BHD has 3 — and the client
 * needs that to format minor units correctly.
 */
@Getter
public class CurrencyResponse {

    private final String code;
    private final String name;
    private final int fractionDigits;

    public CurrencyResponse(String code, String name, int fractionDigits) {
        this.code = code;
        this.name = name;
        this.fractionDigits = fractionDigits;
    }
}
