package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.BudgetPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A spending cap for a category (or overall) across a recurring period (§1.7).
 * Stores the cap only; "spent" is computed from EXPENSE transactions.
 */
@Getter
@Setter
@Entity
@Table(name = "budget")
public class Budget extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** FK → category. Null ⇒ an overall budget. Must be an EXPENSE category if set. */
    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BudgetPeriod period;

    /** Minor units cap for the period. */
    @Column(name = "amount_limit", nullable = false)
    private long amountLimit;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Epoch millis; anchors the period cycle. */
    @Column(name = "start_date", nullable = false)
    private long startDate;

    /** Carry unused amount into the next period. */
    @Column(nullable = false)
    private boolean rollover;

    /** Reuses the ACTIVE / ARCHIVED enum (§1.7). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AccountStatus status = AccountStatus.ACTIVE;
}
