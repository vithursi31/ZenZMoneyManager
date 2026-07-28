package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.Role;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.entity.Verification.Purpose;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.EmailValidator;
import com.zenzmoney.core.util.PasswordValidator;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import com.zenzmoney.core.web.dto.RegisterRequest;
import com.zenzmoney.core.web.dto.RegisterResponse;
import org.slf4j.Logger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class RegistrationService {

    /** Account creation and activation — the start of the trail every other audit line hangs off. */
    private static final Logger audit = AppLog.AUDIT;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final EmailSender emailSender;
    private final OtpService otpService;

    public RegistrationService(UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               JwtTokenService jwtTokenService,
                               EmailSender emailSender,
                               OtpService otpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.emailSender = emailSender;
        this.otpService = otpService;
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

        audit.info("Account registered for {} (user {}, authMode=password, roles={})",
                email, user.getId(), user.getRoles());

        String code = otpService.issue(email, Purpose.VERIFY_EMAIL);
        emailSender.sendVerificationCode(email, code);

        return new RegisterResponse(user.getId(), email, "Verification code sent");
    }

    /**
     * Confirms the OTP emailed at registration, activates the account, and
     * returns access/refresh tokens so the app can proceed straight to a
     * logged-in state.
     */
    @Transactional
    public AuthenticationResponse verifyEmail(String emailRaw, String code) {
        String email = emailRaw == null ? null : emailRaw.toLowerCase().trim();
        EmailValidator.validate(email)
                .ifPresent(m -> { throw new BadRequestException(m); });

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("No account found for that email"));

        otpService.verify(email, code, Purpose.VERIFY_EMAIL);

        user.setStatus("active");
        user.setEmailVerified(true);
        userRepository.save(user);

        audit.info("Email verified for {} (user {}) — account activated, session issued",
                email, user.getId());

        return new AuthenticationResponse(
                jwtTokenService.generateAccessToken(user.getEmail()),
                jwtTokenService.generateRefreshToken(user.getEmail()));
    }
}
