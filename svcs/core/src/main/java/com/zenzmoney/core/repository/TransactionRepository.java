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
     *
     * <p>A null {@code accountId} spans every account the user holds, which is the
     * monthly position as §1.10 defines it; a non-null one narrows to that account
     * for the home screen's account picker.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId AND t.type = :type "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "AND (:accountId IS NULL OR t.accountId = :accountId)")
    long sumAmountByTypeInWindow(@Param("userId") String userId,
                                 @Param("type") TransactionType type,
                                 @Param("from") long from,
                                 @Param("to") long to,
                                 @Param("accountId") String accountId);

    /** Σ EXPENSE for one category in the window (a category budget). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId AND t.categoryId = :categoryId "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to")
    long sumExpenseByCategoryInWindow(@Param("userId") String userId,
                                      @Param("categoryId") String categoryId,
                                      @Param("from") long from,
                                      @Param("to") long to);

    /**
     * Every (category, direction) bucket in the window, biggest first — the category
     * breakdown behind the reports (F-1.9 / F-1.19). Grouped and summed in SQL: the
     * result set is one row per category per direction, not one per transaction.
     *
     * <p>{@code Category} is joined on the raw id because entities here hold foreign
     * keys rather than associations; joining it server-side means a pie chart doesn't
     * need a second call to label and colour itself.
     */
    @Query("SELECT new com.zenzmoney.core.repository.CategoryBreakdownRow("
            + "c.id, c.name, c.parentId, c.color, c.icon, t.type, SUM(t.amount), COUNT(t)) "
            + "FROM Transaction t JOIN Category c ON c.id = t.categoryId "
            + "WHERE t.userId = :userId "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "AND (:accountId IS NULL OR t.accountId = :accountId) "
            + "GROUP BY c.id, c.name, c.parentId, c.color, c.icon, t.type "
            + "ORDER BY SUM(t.amount) DESC")
    List<CategoryBreakdownRow> categoryTotalsInWindow(@Param("userId") String userId,
                                               @Param("from") long from,
                                               @Param("to") long to,
                                               @Param("accountId") String accountId);

    /** Σ EXPENSE across all categories in the window (an overall budget). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to")
    long sumExpenseInWindow(@Param("userId") String userId,
                            @Param("from") long from,
                            @Param("to") long to);

    /**
     * Σ EXPENSE per category in the window, biggest first — the breakdown behind
     * "where does my money go?" (F-1.16).
     *
     * <p>Aggregated in the database rather than by summing rows in Java: the answer
     * is a handful of numbers, and a user with a busy month should not have their
     * whole ledger loaded into memory to produce them.
     */
    @Query("SELECT new com.zenzmoney.core.repository.CategoryTotal(t.categoryId, SUM(t.amount)) "
            + "FROM Transaction t "
            + "WHERE t.userId = :userId "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "GROUP BY t.categoryId "
            + "ORDER BY SUM(t.amount) DESC")
    List<CategoryTotal> sumExpenseByCategoryInWindowGrouped(@Param("userId") String userId,
                                                            @Param("from") long from,
                                                            @Param("to") long to);
}
