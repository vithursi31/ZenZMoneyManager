package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
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
            throw new BadRequestException(Msg.SOCIAL_NO_PASSWORD, user.getAuthMode());
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.warn("Password change denied for {} (user {}) — wrong current password",
                    user.getEmail(), user.getId());
            throw new UnauthorizedException(ServiceCodes.SC_CURRENT_PASSWORD_INVALID);
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        audit.warn("Password changed for {} (user {})", user.getEmail(), user.getId());
    }
}
