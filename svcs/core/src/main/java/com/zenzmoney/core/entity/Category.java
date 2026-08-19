package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.CategoryKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "category")
public class Category extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CategoryKind kind;

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(length = 20)
    private String color;

    @Column(length = 50)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
