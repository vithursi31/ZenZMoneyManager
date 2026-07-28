package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update — a null field means "leave unchanged". Currency, type, and
 * opening balance are intentionally not editable here (single-currency MVP; the
 * opening balance is part of the balance derivation).
 */
@Getter
@Setter
public class UpdateAccountRequest {

    @Size(max = 300)
    private String name;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;

    private Integer sortOrder;
}
