package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.GoalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A target the user is saving toward (§1.9, F-3.1). Progress (saved / remaining)
 * is derived from {@link GoalContribution} rows, never stored, so the goal can
 * never diverge from what was actually put aside.
 *
 * <p><b>Phase 3, built early.</b> Savings goals moved out of the MVP in BRD v1.0;
 * this backend predates that and is kept because Phase 3 commits to it.
 */
@Getter
@Setter
@Entity
@Table(name = "savings_goal")
public class SavingsGoal extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 300)
    private String name;

    /** Minor units. */
    @Column(name = "target_amount", nullable = false)
    private long targetAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Epoch millis; nullable soft deadline. */
    @Column(name = "target_date")
    private Long targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private GoalStatus status = GoalStatus.ACTIVE;

    @Column(length = 20)
    private String color;

    @Column(length = 50)
    private String icon;
}
