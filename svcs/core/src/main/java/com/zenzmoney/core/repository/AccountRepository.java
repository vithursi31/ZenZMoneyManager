package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.AccountStatus;
import com.zenzmoney.core.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByUserId(String userId);

    List<Account> findByUserIdAndStatus(String userId, AccountStatus status);

    Optional<Account> findByIdAndUserId(String id, String userId);

    Optional<Account> findFirstByUserIdAndStatusOrderByCreatedTimeAsc(String userId, AccountStatus status);

    long countByUserIdAndStatus(String userId, AccountStatus status);
}
