package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.RecurringTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecurringTransactionRepository extends JpaRepository<RecurringTransaction, String> {

    List<RecurringTransaction> findByUserId(String userId);

    Optional<RecurringTransaction> findByIdAndUserId(String id, String userId);

    /** Drives the generation job: active templates whose next run is due. */
    List<RecurringTransaction> findByActiveTrueAndNextRunDateLessThanEqual(long now);
}
