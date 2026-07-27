package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * QuerydslPredicateExecutor is extended so the search/filter feature (F-1.19)
 * can build dynamic predicates over date range, category, account, amount, etc.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, String>, QuerydslPredicateExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(String id, String userId);

    List<Transaction> findByUserIdAndAccountId(String userId, String accountId);

    List<Transaction> findByAccountId(String accountId);

    List<Transaction> findByTransferAccountId(String transferAccountId);
}
