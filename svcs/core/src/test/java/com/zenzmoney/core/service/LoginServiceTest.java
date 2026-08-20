package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The login failures are one code on purpose. A client that could tell "no such account" from
 * "wrong password" could enumerate which emails have accounts here — the message was already
 * identical, so a differing code was the whole leak.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginServiceTest {

    private static final String PASSWORD = "C0rrect-Pass!";

    @Mock UserRepository userRepository;
    @Mock JwtTokenService jwtTokenService;
    @Mock RedisRateLimitService rateLimitService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private LoginService service() {
        return new LoginService(userRepository, passwordEncoder, jwtTokenService, rateLimitService);
    }

    private User activeUser() {
        User u = new User();
        u.setId("u1");
        u.setEmail("someone@example.com");
        u.setAuthMode("password");
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return u;
    }

    @Test
    void unknownEmailAndWrongPassword_areIndistinguishable() {
        when(rateLimitService.tryConsumeOrDeny(anyString(), any())).thenReturn(RateLimitResult.allow());

        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        UnauthorizedException unknownEmail = assertThrows(UnauthorizedException.class,
                () -> service().login("nobody@example.com", PASSWORD));

        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(activeUser()));
        UnauthorizedException wrongPassword = assertThrows(UnauthorizedException.class,
                () -> service().login("someone@example.com", "not-the-password"));

        assertEquals(ServiceCodes.SC_INVALID_CREDENTIALS.code(),
                unknownEmail.getStatusCode().code());
        assertEquals(unknownEmail.getStatusCode().code(), wrongPassword.getStatusCode().code());
        assertEquals(unknownEmail.getMessage(), wrongPassword.getMessage());
    }

    @Test
    void lockedAccount_hasItsOwnCode() {
        User u = activeUser();
        u.setLocked(true);
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(u));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service().login("someone@example.com", PASSWORD));

        assertEquals(ServiceCodes.SC_ACCOUNT_LOCKED.code(), ex.getStatusCode().code());
    }

    @Test
    void unverifiedAccount_hasItsOwnCode() {
        User u = activeUser();
        u.setStatus(UserStatus.PENDING);
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(u));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service().login("someone@example.com", PASSWORD));

        assertEquals(ServiceCodes.SC_ACCOUNT_SUSPENDED.code(), ex.getStatusCode().code());
    }

    /** A social-login account gets a code the client can turn into "use the Google button". */
    @Test
    void socialLoginAccount_hasItsOwnCode() {
        User u = activeUser();
        u.setAuthMode("google");
        u.setSystemGeneratedPassword(true);
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(u));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service().login("someone@example.com", PASSWORD));

        assertEquals(ServiceCodes.SC_ACCOUNT_USES_SOCIAL_LOGIN.code(), ex.getStatusCode().code());
    }
}
