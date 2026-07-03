package com.habit.core.repository;

import com.habit.common.domain.HabitStatus;
import com.habit.core.entity.Habit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HabitRepository extends JpaRepository<Habit, String> {

    List<Habit> findByUserIdAndStatusOrderBySortOrderAsc(String userId, HabitStatus status);

    long countByUserId(String userId);
}
