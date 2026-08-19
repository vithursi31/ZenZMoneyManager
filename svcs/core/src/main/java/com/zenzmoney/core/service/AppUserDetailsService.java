package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import org.slf4j.Logger;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class AppUserDetailsService implements UserDetailsService {

    /**
     * Audited because reaching here with an unknown email means a token passed signature
     * verification but names a user who no longer exists — a deleted account still holding a valid
     * token, or a forged token signed with a leaked key. Both are worth seeing.
     */
    private static final Logger audit = AppLog.AUDIT;

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        User u = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    audit.warn("Principal lookup failed for {} — token subject has no account", email);
                    return new UsernameNotFoundException("User not found: " + email);
                });

        return org.springframework.security.core.userdetails.User.builder()
                .username(u.getEmail())
                .password(u.getPasswordHash())
                .authorities(u.getRoles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                        .collect(Collectors.toList()))
                .accountLocked(u.isLocked())
                .disabled(u.getStatus() != UserStatus.ACTIVE)
                .build();
    }
}
