package com.zenzmoney.core.repository;

import com.zenzmoney.core.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    /**
     * The provider's stable subject, provider-qualified ({@code "apple:0012.abc"}). Matched
     * before email on social sign-in so an Apple private-relay rotation resolves to the same
     * account instead of creating a second one.
     */
    Optional<User> findByOauthSubject(String oauthSubject);

    boolean existsByEmail(String email);
}
