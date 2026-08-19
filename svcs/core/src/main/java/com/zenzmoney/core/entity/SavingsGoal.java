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

@Getter
@Setter
@Entity
@Table(name = "savings_goal")
public class SavingsGoal extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "target_amount", nullable = false)
    private long targetAmount;

    @Column(nullable = false, length = 3)
    private String currency;

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
