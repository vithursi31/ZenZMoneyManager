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
@Table(name = "habit_entry")
public class HabitEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "entry_date", nullable = false)
    private Long entryDate;

    @Column(nullable = false)
    private boolean completed;

    private Double value;

    @Column(length = 500)
    private String note;
}
