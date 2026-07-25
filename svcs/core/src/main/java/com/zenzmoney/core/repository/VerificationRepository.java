package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.Verification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationRepository extends JpaRepository<Verification, String> {

    /**
     * The most recent still-pending code for an email + purpose. Only one code
     * is ever active at a time: {@code OtpService} supersedes older pending rows
     * when a new code is issued.
     */
    Optional<Verification> findFirstByEmailAndPurposeAndStatusOrderByCreatedTimeDesc(
            String email, String purpose, String status);
}
