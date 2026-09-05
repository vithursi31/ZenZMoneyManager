package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.TransactionStatus;
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
 *
 * <p><b>Every total here filters {@code status = ACTIVE}, and any new one must too.</b>
 * Deleting is soft (§1.6), so a deleted row is still in the table and a sum that forgets
 * the filter keeps counting money the user removed — silently, and in the monthly
 * position (§1.10), which is the figure the whole app is built around. The same applies
 * to any predicate F-1.9 adds: a QueryDSL search without the status term will surface
 * deleted rows.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, String>, QuerydslPredicateExecutor<Transaction> {

    /**
     * Any row the user owns, <b>deleted ones included</b>. For the paths that must reach
     * a deleted row on purpose — undoing a chat write, and resolving the transaction a
     * chat turn or goal contribution points at.
     */
    Optional<Transaction> findByIdAndUserId(String id, String userId);

    /** One live row. What every read, edit and delete goes through. */
    Optional<Transaction> findByIdAndUserIdAndStatus(String id, String userId, TransactionStatus status);

    List<Transaction> findByUserIdAndStatus(String userId, TransactionStatus status);

    /**
     * Live rows of exactly this amount, newest first — how a delete request from chat
     * ("remove the 2,500 restaurant expense") finds what the user means. The amount is
     * matched exactly because it is the one thing they always state precisely; anything
     * looser would offer to delete a row they did not name.
     */
    List<Transaction> findByUserIdAndStatusAndAmountOrderByTxnDateDesc(
            String userId, TransactionStatus status, long amount);

    /**
     * True once the user has recorded anything — the test that freezes their currency
     * (§0.3). <b>Counts deleted rows on purpose:</b> those rows still carry the currency
     * they were written in, so letting a user delete their last transaction and then
     * switch currency would leave history that could never be restored coherently.
     */
    boolean existsByUserId(String userId);

    /** True if any <b>live</b> transaction is classified under this category. */
    boolean existsByCategoryIdAndStatus(String categoryId, TransactionStatus status);

    /** True if any <b>live</b> transaction references this payee. */
    boolean existsByPayeeIdAndStatus(String payeeId, TransactionStatus status);

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
            + "AND t.status = com.zenzmoney.common.domain.TransactionStatus.ACTIVE "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "AND (:accountId IS NULL OR t.accountId = :accountId)")
    long sumAmountByTypeInWindow(@Param("userId") String userId,
                                 @Param("type") TransactionType type,
                                 @Param("from") long from,
                                 @Param("to") long to,
                                 @Param("accountId") String accountId);

    /**
     * Σ EXPENSE for one category in the window (a category budget). A budget targets
     * one account (§1.7), so {@code accountId} narrows the sum to it — otherwise two
     * budgets for the same category on different accounts would report the same
     * spend. Null spans every account.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId AND t.categoryId = :categoryId "
            + "AND t.status = com.zenzmoney.common.domain.TransactionStatus.ACTIVE "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "AND (:accountId IS NULL OR t.accountId = :accountId)")
    long sumExpenseByCategoryInWindow(@Param("userId") String userId,
                                      @Param("categoryId") String categoryId,
                                      @Param("from") long from,
                                      @Param("to") long to,
                                      @Param("accountId") String accountId);

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
            + "AND t.status = com.zenzmoney.common.domain.TransactionStatus.ACTIVE "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "AND (:accountId IS NULL OR t.accountId = :accountId) "
            + "GROUP BY c.id, c.name, c.parentId, c.color, c.icon, t.type "
            + "ORDER BY SUM(t.amount) DESC")
    List<CategoryBreakdownRow> categoryTotalsInWindow(@Param("userId") String userId,
                                               @Param("from") long from,
                                               @Param("to") long to,
                                               @Param("accountId") String accountId);

    /** Σ EXPENSE across all categories in the window (an overall budget), optionally one account. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.userId = :userId "
            + "AND t.status = com.zenzmoney.common.domain.TransactionStatus.ACTIVE "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "AND (:accountId IS NULL OR t.accountId = :accountId)")
    long sumExpenseInWindow(@Param("userId") String userId,
                            @Param("from") long from,
                            @Param("to") long to,
                            @Param("accountId") String accountId);

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
            + "AND t.status = com.zenzmoney.common.domain.TransactionStatus.ACTIVE "
            + "AND t.type = com.zenzmoney.common.domain.TransactionType.EXPENSE "
            + "AND t.txnDate >= :from AND t.txnDate < :to "
            + "GROUP BY t.categoryId "
            + "ORDER BY SUM(t.amount) DESC")
    List<CategoryTotal> sumExpenseByCategoryInWindowGrouped(@Param("userId") String userId,
                                                            @Param("from") long from,
                                                            @Param("to") long to);
}
