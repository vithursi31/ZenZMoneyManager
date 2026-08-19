package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.PasswordValidator;
import org.slf4j.Logger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangePasswordService {

    private static final Logger audit = AppLog.AUDIT;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUser;

    public ChangePasswordService(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 CurrentUserService currentUser) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        User user = currentUser.requireUser();

        PasswordValidator.validate(newPassword)
                .ifPresent(m -> { throw new BadRequestException(m); });

        if (!"password".equals(user.getAuthMode())) {
            throw new BadRequestException(
                    "This account uses " + user.getAuthMode() + " login and has no password to change.");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.warn("Password change denied for {} (user {}) — wrong current password",
                    user.getEmail(), user.getId());
            throw new UnauthorizedException("INVALID_PASSWORD", "Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        audit.warn("Password changed for {} (user {})", user.getEmail(), user.getId());
    }
}
