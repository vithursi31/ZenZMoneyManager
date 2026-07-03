package com.habit.core.repository;

import com.habit.core.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, String> {

    List<Reminder> findByHabitId(String habitId);

    List<Reminder> findByUserId(String userId);
}
