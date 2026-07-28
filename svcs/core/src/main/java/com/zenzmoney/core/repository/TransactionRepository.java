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
 * QuerydslPredicateExecutor is extended so the search/filter feature (F-1.19)
 * can build dynamic predicates over date range, category, account, amount, etc.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, String>, QuerydslPredicateExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(String id, String userId);

    List<Transaction> findByUserId(String userId);

    List<Transaction> findByUserIdAndAccountId(String userId, String accountId);

    List<Transaction> findByAccountId(String accountId);

    List<Transaction> findByTransferAccountId(String transferAccountId);

    /** True if any transaction uses this account as its source. */
    boolean existsByAccountId(String accountId);

    /** True if any transaction uses this account as a transfer destination. */
    boolean existsByTransferAccountId(String transferAccountId);

    /** True if any transaction is classified under this category. */
    boolean existsByCategoryId(String categoryId);

    /** True if any transaction references this payee. */
    boolean existsByPayeeId(String payeeId);

    // --- balance derivation (§1.10): sums an account's ledger by direction ---

    /** Σ amount for an account as the source, for one type (INCOME/EXPENSE/TRANSFER-out). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.accountId = :accountId AND t.type = :type")
    long sumAmountByAccountIdAndType(@Param("accountId") String accountId,
                                     @Param("type") TransactionType type);

    /** Σ amount transferred INTO an account (it is the transfer destination). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t "
            + "WHERE t.transferAccountId = :accountId")
    long sumTransferInByAccountId(@Param("accountId") String accountId);
}
