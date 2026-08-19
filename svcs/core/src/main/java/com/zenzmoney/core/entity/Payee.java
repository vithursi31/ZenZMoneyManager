package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "payee")
public class Payee extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 300)
    private String normalizedName;

    @Column(length = 20)
    private String color;

    @Column(length = 50)
    private String icon;
}
