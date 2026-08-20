package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.BudgetPeriod;
import com.zenzmoney.common.domain.BudgetStatus;
import com.zenzmoney.core.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, String> {

    List<Budget> findByUserId(String userId);

    Optional<Budget> findByIdAndUserId(String id, String userId);

    List<Budget> findByUserIdAndCategoryId(String userId, String categoryId);

    /** The one slot a new budget competes for: same account, period type and period (§1.7). */
    List<Budget> findByUserIdAndAccountIdAndPeriodAndPeriodKeyAndStatus(
            String userId, String accountId, BudgetPeriod period, String periodKey, BudgetStatus status);

    /** Every budget the caller planned for one period — the month summary's input. */
    List<Budget> findByUserIdAndPeriodAndPeriodKeyAndStatus(
            String userId, BudgetPeriod period, String periodKey, BudgetStatus status);

    /**
     * True if any budget that still counts targets this category — the guard on
     * deleting a category (§1.5). Deleting a budget is soft, so a `DELETED` row must
     * not keep its category hostage forever.
     */
    boolean existsByCategoryIdAndStatusNot(String categoryId, BudgetStatus status);
}
