package com.habit.core.entity;

import com.habit.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "reminder")
public class Reminder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "reminder_time", nullable = false)
    private Long reminderTime;

    @Column(name = "days_of_week", length = 100)
    private String daysOfWeek;

    private boolean enabled = true;
}
