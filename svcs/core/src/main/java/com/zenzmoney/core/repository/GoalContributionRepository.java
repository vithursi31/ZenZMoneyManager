package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.GoalContribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GoalContributionRepository extends JpaRepository<GoalContribution, String> {

    List<GoalContribution> findByGoalId(String goalId);

    List<GoalContribution> findByTransactionId(String transactionId);

    Optional<GoalContribution> findByIdAndGoalId(String id, String goalId);

    /** Σ contributed toward a goal — the derived {@code saved} figure (§1.9). */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM GoalContribution c WHERE c.goalId = :goalId")
    long sumAmountByGoalId(@Param("goalId") String goalId);
}
