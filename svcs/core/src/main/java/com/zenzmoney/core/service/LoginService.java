package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import org.slf4j.Logger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Service
public class LoginService {

    /**
     * Login outcomes go to audit.log. The reason code is recorded but never the password or the
     * issued tokens: this file is retained for a year precisely so an account-takeover attempt can
     * be reconstructed from it, which is worthless if reading it hands over live credentials.
     */
    private static final Logger audit = AppLog.AUDIT;

    /**
     * Per-account throttle on wrong-password attempts — mirrors {@code OtpService.OTP_POLICY}:
     * fail-closed, both windows checked atomically. A denial also hard-locks the account
     * (same consequence a rapid brute-force always had), so the short window catches a fast
     * attacker at roughly the same attempt count as before, and the daily window catches a
     * slow, spaced-out one that would otherwise dodge it.
     */
    private static final RateLimitPolicy LOGIN_POLICY = RateLimitPolicy
            .of(5, Duration.ofMinutes(15))
            .and(10, Duration.ofDays(1));

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RedisRateLimitService rateLimitService;

    public LoginService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtTokenService jwtTokenService,
                        RedisRateLimitService rateLimitService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.rateLimitService = rateLimitService;
    }

    @Transactional
    public AuthenticationResponse login(String emailRaw, String password) {
        if (emailRaw == null || password == null) {
            throw new UnauthorizedException(ServiceCodes.SC_INVALID_CREDENTIALS);
        }
        String email = emailRaw.toLowerCase().trim();

        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            audit.info("Login denied for {} — no such account", email);
            // Same code and message as a wrong password: a client that could tell the two apart
            // could enumerate which emails hold accounts here.
            throw new UnauthorizedException(ServiceCodes.SC_INVALID_CREDENTIALS);
        }
        User user = found.get();

        if (user.isLocked()) {
            audit.warn("Login denied for {} — account locked", email);
            throw new UnauthorizedException(ServiceCodes.SC_ACCOUNT_LOCKED);
        }

        if (!"password".equals(user.getAuthMode()) && user.isSystemGeneratedPassword()) {
            String provider = user.getAuthMode();
            if (provider != null && !provider.isEmpty()) {
                provider = Character.toUpperCase(provider.charAt(0)) + provider.substring(1);
            }
            audit.info("Login denied for {} — account uses {} login", email, user.getAuthMode());
            throw new UnauthorizedException(ServiceCodes.SC_ACCOUNT_USES_SOCIAL_LOGIN.with(
                    "This account was created using " + provider
                            + " login. Please use 'Login with " + provider + "'."));
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            audit.info("Login denied for {} — account status {}", email, user.getStatus());
            throw new UnauthorizedException(ServiceCodes.SC_ACCOUNT_SUSPENDED);
        }

        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            user.setLastLoginTime(System.currentTimeMillis());
            userRepository.save(user);
            audit.info("Login succeeded for {} (user {})", email, user.getId());
            return new AuthenticationResponse(
                    jwtTokenService.generateAccessToken(user.getEmail()),
                    jwtTokenService.generateRefreshToken(user.getEmail()));
        }

        RateLimitResult rl = rateLimitService.tryConsumeOrDeny("login:" + email, LOGIN_POLICY);
        if (!rl.allowed()) {
            user.setLocked(true);
            userRepository.save(user);
            audit.warn("Account {} locked — wrong-password attempts exceeded the rate limit, retry after {}s",
                    email, rl.retryAfterSeconds());
            throw new TooManyRequestsException(ServiceCodes.SC_LOGIN_RATE_LIMIT_EXCEEDED.with(
                    "Too many failed login attempts. Your account has been locked; "
                            + "reset your password to regain access."),
                    rl.retryAfterSeconds());
        }

        audit.info("Login denied for {} — wrong password", email);
        throw new UnauthorizedException(ServiceCodes.SC_INVALID_CREDENTIALS);
    }
}
