package com.habit.core.service;

import com.habit.common.exception.BadRequestException;
import com.habit.common.exception.UnauthorizedException;
import com.habit.core.entity.User;
import com.habit.core.repository.UserRepository;
import com.habit.core.util.EmailValidator;
import com.habit.core.util.PasswordValidator;
import com.habit.core.web.dto.AuthenticationResponse;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final EmailSender emailSender;
    private final String appBaseUrl;

    public PasswordResetService(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                JwtTokenService jwtTokenService,
                                EmailSender emailSender,
                                @Value("${habit.app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.emailSender = emailSender;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public void forgotPassword(String emailRaw) {
        EmailValidator.validate(emailRaw)
                .ifPresent(m -> { throw new BadRequestException(m); });
        String email = emailRaw.toLowerCase().trim();

        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            log.info("Password reset requested for unknown email {}", email);
            return;
        }
        User user = found.get();

        if (!"password".equals(user.getAuthMode())) {
            log.info("Password reset skipped for {} — account uses {} login", email, user.getAuthMode());
            return;
        }

        String token = jwtTokenService.generatePasswordResetToken(user.getId());
        String link = appBaseUrl + "/reset-password?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        emailSender.sendPasswordResetLink(email, link);
    }

    @Transactional
    public AuthenticationResponse resetPassword(String token, String newPassword) {
        PasswordValidator.validate(newPassword)
                .ifPresent(m -> { throw new BadRequestException(m); });

        Claims claims = jwtTokenService.extractClaims(token);
        if (!JwtTokenService.TYPE_RESET.equals(jwtTokenService.extractTokenType(claims))) {
            throw new UnauthorizedException("INVALID_TOKEN", "Not a password-reset token");
        }

        String userId = claims.getSubject();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("INVALID_TOKEN", "User not found"));

        if (!"password".equals(user.getAuthMode())) {
            throw new BadRequestException(
                    "This account uses " + user.getAuthMode() + " login and cannot reset a password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setSystemGeneratedPassword(false);
        user.setLoginAttempts(0);
        user.setLocked(false);
        if (!"active".equals(user.getStatus())) {
            user.setStatus("active");
        }
        user.setEmailVerified(true);
        user.setLastLoginTime(System.currentTimeMillis());
        userRepository.save(user);

        return new AuthenticationResponse(
                jwtTokenService.generateAccessToken(user.getEmail()),
                jwtTokenService.generateRefreshToken(user.getEmail()));
    }
}
