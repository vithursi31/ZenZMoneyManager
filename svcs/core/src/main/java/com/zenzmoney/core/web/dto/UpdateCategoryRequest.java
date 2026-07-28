package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update — a null field is left unchanged. Kind and parent are fixed at
 * creation (changing them would risk breaking the one-level / same-kind rules).
 */
@Getter
@Setter
public class UpdateCategoryRequest {

    @Size(max = 200)
    private String name;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;

    private Integer sortOrder;
}
