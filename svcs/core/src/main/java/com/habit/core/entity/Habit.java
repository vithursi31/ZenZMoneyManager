package com.habit.core.entity;

import com.habit.common.domain.BaseEntity;
import com.habit.common.domain.HabitStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "habit")
public class Habit extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 100)
    private String category;

    @Column(nullable = false, length = 50)
    private String frequency = "daily";

    @Column(name = "target_per_period", nullable = false)
    private int targetPerPeriod = 1;

    @Column(length = 20)
    private String color;

    @Column(length = 50)
    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private HabitStatus status = HabitStatus.ACTIVE;

    @Column(name = "sort_order")
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @OneToMany(mappedBy = "habit", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HabitEntry> entries = new ArrayList<>();

    @OneToMany(mappedBy = "habit", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Reminder> reminders = new ArrayList<>();
}
