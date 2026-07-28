package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Payee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayeeRepository extends JpaRepository<Payee, String> {

    List<Payee> findByUserId(String userId);

    Optional<Payee> findByIdAndUserId(String id, String userId);

    /** Dedup key for resolve-or-create — one payee per (user, normalizedName). */
    Optional<Payee> findByUserIdAndNormalizedName(String userId, String normalizedName);
}
