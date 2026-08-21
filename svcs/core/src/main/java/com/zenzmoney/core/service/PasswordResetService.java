package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.entity.Verification.Purpose;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.EmailValidator;
import com.zenzmoney.core.util.PasswordValidator;
import com.zenzmoney.core.util.SupportedLanguages;
import com.zenzmoney.core.web.dto.AuthenticationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * Reset is the documented account-takeover surface (see the Security Posture notes), so both
     * ends of the flow are audited: the email a code was requested for, and the email whose password
     * actually changed. If those ever disagree, the server-side identity binding has been broken.
     */
    private static final Logger audit = AppLog.AUDIT;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final EmailSender emailSender;
    private final OtpService otpService;
    private final SupportedLanguages supportedLanguages;

    public PasswordResetService(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                JwtTokenService jwtTokenService,
                                EmailSender emailSender,
                                OtpService otpService,
                                SupportedLanguages supportedLanguages) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.emailSender = emailSender;
        this.otpService = otpService;
        this.supportedLanguages = supportedLanguages;
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

        audit.info("Password reset code requested for {}", email);
        String code = otpService.issue(email, Purpose.RESET_PASSWORD);
        emailSender.sendPasswordResetCode(email, code, supportedLanguages.resolveOrDefault(user.getLanguage()));
    }

    @Transactional
    public AuthenticationResponse resetPassword(String emailRaw, String code, String newPassword) {
        String email = emailRaw == null ? null : emailRaw.toLowerCase().trim();
        EmailValidator.validate(email)
                .ifPresent(m -> { throw new BadRequestException(m); });
        PasswordValidator.validate(newPassword)
                .ifPresent(m -> { throw new BadRequestException(m); });

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException(Msg.EMAIL_UNKNOWN));

        if (!"password".equals(user.getAuthMode())) {
            throw new BadRequestException(Msg.SOCIAL_NO_RESET, user.getAuthMode());
        }

        otpService.verify(email, code, Purpose.RESET_PASSWORD);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setSystemGeneratedPassword(false);
        user.setLocked(false);
        if (user.getStatus() != UserStatus.ACTIVE) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setEmailVerified(true);
        user.setLastLoginTime(System.currentTimeMillis());
        userRepository.save(user);

        // The email here is the one the OTP was validated against, and the user was resolved from
        // that same email — the pair in this line is what proves the binding held.
        audit.warn("Password reset completed for {} (user {}) — lock cleared, session issued",
                email, user.getId());

        return new AuthenticationResponse(
                jwtTokenService.generateAccessToken(user.getEmail()),
                jwtTokenService.generateRefreshToken(user.getEmail()));
    }
}
