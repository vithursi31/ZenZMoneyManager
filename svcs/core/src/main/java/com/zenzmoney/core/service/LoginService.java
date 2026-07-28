package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import org.slf4j.Logger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class LoginService {

    /**
     * Login outcomes go to audit.log. The reason code is recorded but never the password or the
     * issued tokens: this file is retained for a year precisely so an account-takeover attempt can
     * be reconstructed from it, which is worthless if reading it hands over live credentials.
     */
    private static final Logger audit = AppLog.AUDIT;

    private static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public LoginService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthenticationResponse login(String emailRaw, String password) {
        if (emailRaw == null || password == null) {
            throw new UnauthorizedException("INVALID_USERNAME", "Invalid email or password");
        }
        String email = emailRaw.toLowerCase().trim();

        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            audit.info("Login denied for {} — no such account", email);
            throw new UnauthorizedException("INVALID_USERNAME", "Invalid email or password");
        }
        User user = found.get();

        if (user.isLocked()) {
            audit.warn("Login denied for {} — account locked", email);
            throw new UnauthorizedException("USER_LOCKED", "Account is locked");
        }

        if (!"password".equals(user.getAuthMode()) && user.isSystemGeneratedPassword()) {
            String provider = user.getAuthMode();
            if (provider != null && !provider.isEmpty()) {
                provider = Character.toUpperCase(provider.charAt(0)) + provider.substring(1);
            }
            audit.info("Login denied for {} — account uses {} login", email, user.getAuthMode());
            throw new UnauthorizedException("VALIDATION_FAILED",
                    "This account was created using " + provider + " login. Please use 'Login with " + provider + "'.");
        }

        if (!"active".equals(user.getStatus())) {
            audit.info("Login denied for {} — account status {}", email, user.getStatus());
            throw new UnauthorizedException("USER_NOT_ACTIVE",
                    "Account is not active. Please verify your email.");
        }

        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            user.setLoginAttempts(0);
            user.setLastLoginTime(System.currentTimeMillis());
            userRepository.save(user);
            audit.info("Login succeeded for {} (user {})", email, user.getId());
            return new AuthenticationResponse(
                    jwtTokenService.generateAccessToken(user.getEmail()),
                    jwtTokenService.generateRefreshToken(user.getEmail()));
        }

        int attempts = user.getLoginAttempts() + 1;
        user.setLoginAttempts(attempts);
        if (attempts > MAX_ATTEMPTS) {
            user.setLocked(true);
            audit.warn("Account {} locked after {} failed login attempts", email, attempts);
        } else {
            audit.info("Login denied for {} — wrong password (attempt {} of {})",
                    email, attempts, MAX_ATTEMPTS);
        }
        userRepository.save(user);
        throw new UnauthorizedException("INVALID_PASSWORD", "Invalid email or password");
    }
}
