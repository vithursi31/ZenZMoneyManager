package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /** A user has at most one account (§1.4), so this is an {@code Optional}, not a list. */
    Optional<Account> findByUserId(String userId);
}
