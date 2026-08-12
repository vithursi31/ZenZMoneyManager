package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * QuerydslPredicateExecutor is extended so the search/filter feature (F-1.9)
 * can build dynamic predicates over date range, category, amount, payee, etc.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, String>, QuerydslPredicateExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(String id, String userId);

    List<Transaction> findByUserId(String userId);

    /** True once the user has recorded anything — the test that freezes their currency (§0.3). */
    boolean existsByUserId(String userId);

    /** True if any transaction is classified under this category. */
    boolean existsByCategoryId(String categoryId);

    /** True if any transaction references this payee. */
    boolean existsByPayeeId(String payeeId);

    // --- monthly position (§1.10) and budget spend (§1.7): sums over a [from, to) window ---

    /**
     * Σ amount for one type in the half-open window {@code [from, to)} — the two
     * queries behind the monthly position (F-1.2) and the dashboard (F-1.17).
     * Served by {@code idx_transaction_user_date}.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId AND t.type = :type "
            + "AND t.txnDate >= :from AND t.txnDate < :to")
    long sumAmountByTypeInWindow(@Param("userId") String userId,
                                 @Param("type") TransactionType type,
                                 @Param("from") long from,
                                 @Param("to") long to);

    /** Σ EXPENSE for one category in the window (a category budget). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId AND t.categoryId = :categoryId "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to")
    long sumExpenseByCategoryInWindow(@Param("userId") String userId,
                                      @Param("categoryId") String categoryId,
                                      @Param("from") long from,
                                      @Param("to") long to);

    /** Σ EXPENSE across all categories in the window (an overall budget). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to")
    long sumExpenseInWindow(@Param("userId") String userId,
                            @Param("from") long from,
                            @Param("to") long to);
}
