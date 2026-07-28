package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.core.entity.Category;
import lombok.Getter;

@Getter
public class CategoryResponse {

    private final String id;
    private final String name;
    private final CategoryKind kind;
    private final String parentId;
    private final String color;
    private final String icon;
    private final int sortOrder;

    private CategoryResponse(Category c) {
        this.id = c.getId();
        this.name = c.getName();
        this.kind = c.getKind();
        this.parentId = c.getParentId();
        this.color = c.getColor();
        this.icon = c.getIcon();
        this.sortOrder = c.getSortOrder();
    }

    public static CategoryResponse of(Category c) {
        return new CategoryResponse(c);
    }
}
