package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.BudgetPeriod;
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
@Table(name = "budget")
public class Budget extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BudgetPeriod period;

    @Column(name = "period_key", nullable = false, length = 7)
    private String periodKey;

    @Column(name = "amount_limit", nullable = false)
    private long amountLimit;

    @Column(nullable = false)
    private boolean rollover;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BudgetStatus status = BudgetStatus.ACTIVE;
}
