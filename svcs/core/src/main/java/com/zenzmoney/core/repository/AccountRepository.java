package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    List<Account> findByUserId(String userId);

    Optional<Account> findByIdAndUserId(String id, String userId);
}
