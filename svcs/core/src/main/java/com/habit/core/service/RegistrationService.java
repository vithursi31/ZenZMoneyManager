package com.habit.core.service;

import com.habit.common.domain.Role;
import com.habit.common.exception.BadRequestException;
import com.habit.common.exception.UnauthorizedException;
import com.habit.core.entity.User;
import com.habit.core.repository.UserRepository;
import com.habit.core.util.EmailValidator;
import com.habit.core.util.PasswordValidator;
import com.habit.core.web.dto.AuthenticationResponse;
import com.habit.core.web.dto.RegisterRequest;
import com.habit.core.web.dto.RegisterResponse;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final EmailSender emailSender;
    private final String appBaseUrl;

    public RegistrationService(UserRepository userRepository,
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
    public RegisterResponse register(RegisterRequest req) {
        String email = req.getEmail() == null ? null : req.getEmail().toLowerCase().trim();

        EmailValidator.validate(email)
                .ifPresent(m -> { throw new BadRequestException(m); });
        PasswordValidator.validate(req.getPassword())
                .ifPresent(m -> { throw new BadRequestException(m); });

        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("Email already in use");
        }

        User user = new User();
        user.setEmail(email);
        user.setDisplayName(req.getDisplayName());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setAuthMode("password");
        user.setStatus("pending");
        user.setEmailVerified(false);
        user.setSystemGeneratedPassword(false);
        user.setRoles(Set.of(Role.USER));
        userRepository.save(user);

        String token = jwtTokenService.generateEmailVerificationToken(user.getId());
        String link = appBaseUrl + "/api/v1/verify-email?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        emailSender.sendVerificationLink(email, link);

        return new RegisterResponse(user.getId(), email, "Verification email sent");
    }

    @Transactional
    public AuthenticationResponse verifyEmail(String token) {
        Claims claims = jwtTokenService.extractClaims(token);
        if (!JwtTokenService.TYPE_VERIFY.equals(jwtTokenService.extractTokenType(claims))) {
            throw new UnauthorizedException("INVALID_TOKEN", "Not an email-verification token");
        }

        String userId = claims.getSubject();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("INVALID_TOKEN", "User not found"));

        if (!"active".equals(user.getStatus())) {
            user.setStatus("active");
        }
        user.setEmailVerified(true);
        userRepository.save(user);

        return new AuthenticationResponse(
                jwtTokenService.generateAccessToken(user.getEmail()),
                jwtTokenService.generateRefreshToken(user.getEmail()));
    }
}
