package com.habit.core.service;

import com.habit.common.exception.UnauthorizedException;
import com.habit.core.entity.User;
import com.habit.core.repository.UserRepository;
import com.habit.core.web.dto.AuthenticationResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class LoginService {

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
            throw new UnauthorizedException("INVALID_USERNAME", "Invalid email or password");
        }
        User user = found.get();

        if (user.isLocked()) {
            throw new UnauthorizedException("USER_LOCKED", "Account is locked");
        }

        if (!"password".equals(user.getAuthMode()) && user.isSystemGeneratedPassword()) {
            String provider = user.getAuthMode();
            if (provider != null && !provider.isEmpty()) {
                provider = Character.toUpperCase(provider.charAt(0)) + provider.substring(1);
            }
            throw new UnauthorizedException("VALIDATION_FAILED",
                    "This account was created using " + provider + " login. Please use 'Login with " + provider + "'.");
        }

        if (!"active".equals(user.getStatus())) {
            throw new UnauthorizedException("USER_NOT_ACTIVE",
                    "Account is not active. Please verify your email.");
        }

        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            user.setLoginAttempts(0);
            user.setLastLoginTime(System.currentTimeMillis());
            userRepository.save(user);
            return new AuthenticationResponse(
                    jwtTokenService.generateAccessToken(user.getEmail()),
                    jwtTokenService.generateRefreshToken(user.getEmail()));
        }

        int attempts = user.getLoginAttempts() + 1;
        user.setLoginAttempts(attempts);
        if (attempts > MAX_ATTEMPTS) {
            user.setLocked(true);
        }
        userRepository.save(user);
        throw new UnauthorizedException("INVALID_PASSWORD", "Invalid email or password");
    }
}
