package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, String> {

    List<Budget> findByUserId(String userId);

    Optional<Budget> findByIdAndUserId(String id, String userId);

    List<Budget> findByUserIdAndCategoryId(String userId, String categoryId);
}
