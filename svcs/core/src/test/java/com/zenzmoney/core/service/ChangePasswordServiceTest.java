package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangePasswordServiceTest {

    private static final String CURRENT_PASSWORD = "Curr3nt-Pass!";
    private static final String NEW_PASSWORD = "NewPassw0rd!";

    @Mock UserRepository userRepository;
    @Mock CurrentUserService currentUser;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private ChangePasswordService service() {
        return new ChangePasswordService(userRepository, passwordEncoder, currentUser);
    }

    private User passwordUser() {
        User u = new User();
        u.setId("u1");
        u.setEmail("someone@example.com");
        u.setAuthMode("password");
        u.setPasswordHash(passwordEncoder.encode(CURRENT_PASSWORD));
        return u;
    }

    @Test
    void changePassword_updatesTheHash_whenCurrentPasswordMatches() {
        User u = passwordUser();
        when(currentUser.requireUser()).thenReturn(u);

        service().changePassword(CURRENT_PASSWORD, NEW_PASSWORD);

        assertNotEquals(passwordEncoder.encode(CURRENT_PASSWORD), u.getPasswordHash());
        assertEquals(true, passwordEncoder.matches(NEW_PASSWORD, u.getPasswordHash()));
        verify(userRepository).save(u);
    }

    @Test
    void changePassword_rejectsAWrongCurrentPassword() {
        User u = passwordUser();
        when(currentUser.requireUser()).thenReturn(u);

        assertThrows(UnauthorizedException.class,
                () -> service().changePassword("not-the-current-password", NEW_PASSWORD));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_rejectsAWeakNewPassword() {
        User u = passwordUser();
        when(currentUser.requireUser()).thenReturn(u);

        assertThrows(BadRequestException.class,
                () -> service().changePassword(CURRENT_PASSWORD, "weak"));
        verify(userRepository, never()).save(any());
    }

    /** An OAuth-created account has a random password the user never knew; there is nothing to change. */
    @Test
    void changePassword_rejectsAnOAuthAccount() {
        User u = passwordUser();
        u.setAuthMode("google");
        when(currentUser.requireUser()).thenReturn(u);

        assertThrows(BadRequestException.class,
                () -> service().changePassword(CURRENT_PASSWORD, NEW_PASSWORD));
        verify(userRepository, never()).save(any());
    }
}
