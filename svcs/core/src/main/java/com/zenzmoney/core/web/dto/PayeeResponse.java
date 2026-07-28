package com.zenzmoney.core.web.dto;

import com.zenzmoney.core.entity.Payee;
import lombok.Getter;

@Getter
public class PayeeResponse {

    private final String id;
    private final String name;
    private final String color;
    private final String icon;

    private PayeeResponse(Payee p) {
        this.id = p.getId();
        this.name = p.getName();
        this.color = p.getColor();
        this.icon = p.getIcon();
    }

    public static PayeeResponse of(Payee p) {
        return new PayeeResponse(p);
    }
}
