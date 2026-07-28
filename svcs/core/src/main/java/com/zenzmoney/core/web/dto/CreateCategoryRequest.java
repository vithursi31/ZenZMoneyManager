package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCategoryRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotNull
    private CategoryKind kind;

    /** Optional parent for a sub-category. Hierarchy is one level deep (§1.5). */
    private String parentId;

    @Size(max = 20)
    private String color;

    @Size(max = 50)
    private String icon;

    private int sortOrder;
}
