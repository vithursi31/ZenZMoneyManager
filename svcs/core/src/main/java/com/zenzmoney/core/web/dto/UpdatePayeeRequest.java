package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Partial update — a null field is left unchanged. Renaming re-derives the normalized key. */
@Getter
@Setter
public class UpdatePayeeRequest {

    @Size(max = 300)
    private String name;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;
}
