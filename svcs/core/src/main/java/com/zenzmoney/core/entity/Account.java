package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "account")
public class Account extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(nullable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();
}
