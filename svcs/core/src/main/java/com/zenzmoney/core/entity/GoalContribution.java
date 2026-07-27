package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A single funding event linking a {@link SavingsGoal} to the real
 * {@link Transaction} that moved money into the backing account (§1.9).
 */
@Getter
@Setter
@Entity
@Table(name = "goal_contribution")
public class GoalContribution extends BaseEntity {

    @Column(name = "goal_id", nullable = false, length = 36)
    private String goalId;

    /** FK → transaction (the TRANSFER into the backing account). Nullable for a manual adjustment. */
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    /** Minor units, positive. */
    @Column(nullable = false)
    private long amount;

    /** Epoch millis. */
    @Column(name = "contributed_at", nullable = false)
    private long contributedAt;

    @Column(length = 500)
    private String note;
}
