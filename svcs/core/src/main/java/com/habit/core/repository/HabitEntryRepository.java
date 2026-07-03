package com.habit.core.repository;

import com.habit.core.entity.HabitEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitEntryRepository extends JpaRepository<HabitEntry, String> {

    List<HabitEntry> findByHabitIdAndEntryDateBetween(String habitId, Long from, Long to);

    Optional<HabitEntry> findByHabitIdAndEntryDate(String habitId, Long entryDate);

    long countByUserIdAndCompletedAndEntryDateBetween(String userId, boolean completed, Long from, Long to);
}
