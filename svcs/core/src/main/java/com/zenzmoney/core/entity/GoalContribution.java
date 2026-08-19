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
@Table(name = "goal_contribution")
public class GoalContribution extends BaseEntity {

    @Column(name = "goal_id", nullable = false, length = 36)
    private String goalId;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "contributed_at", nullable = false)
    private long contributedAt;

    @Column(length = 500)
    private String note;
}
