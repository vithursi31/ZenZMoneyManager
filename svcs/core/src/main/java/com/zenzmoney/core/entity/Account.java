package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.AccountType;
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

/**
 * A place where money is held (cash, bank, card, savings, wallet). Owned by one
 * user. See domain doc §1.4.
 */
@Getter
@Setter
@Entity
@Table(name = "account")
public class Account extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 300)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AccountType type;

    /**
     * ISO-4217. In the MVP this always equals the owner's active currency; stored
     * per-account so a later multi-currency phase is additive (§0.3).
     */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Minor units at account creation. */
    @Column(name = "opening_balance", nullable = false)
    private long openingBalance;

    /** Minor units. Materialized from the ledger — not client-settable (§1.10). */
    @Column(name = "current_balance", nullable = false)
    private long currentBalance;

    @Column(length = 20)
    private String color;

    @Column(length = 50)
    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();
}
