package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.SavingsGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, String> {

    List<SavingsGoal> findByUserId(String userId);

    Optional<SavingsGoal> findByIdAndUserId(String id, String userId);
}
