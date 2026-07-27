package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.GoalContribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoalContributionRepository extends JpaRepository<GoalContribution, String> {

    List<GoalContribution> findByGoalId(String goalId);

    List<GoalContribution> findByTransactionId(String transactionId);
}
