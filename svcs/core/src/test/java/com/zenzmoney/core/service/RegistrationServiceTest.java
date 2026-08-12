package com.zenzmoney.core.service;

import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the preferences registration seeds (F-1.27). The account itself is not
 * created here — it stays lazily provisioned — so what matters is that the user
 * lands with a usable currency when the client told us enough to pick one, and
 * with none rather than a wrong one when it did not.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtTokenService jwtTokenService;
    @Mock EmailSender emailSender;
    @Mock OtpService otpService;
    @InjectMocks RegistrationService registrationService;

    private RegisterRequest req(String locale, String timezone) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail("someone@example.com");
        r.setPassword("Passw0rd!");
        r.setDisplayName("Someone");
        r.setLocale(locale);
        r.setTimezone(timezone);
        return r;
    }

    private User register(RegisterRequest r) {
        when(userRepository.existsByEmail("someone@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(otpService.issue(anyString(), any())).thenReturn("123456");

        registrationService.register(r);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void register_seedsPreferences_fromTheReportedLocale() {
        User u = register(req("si-LK", "Asia/Colombo"));

        assertEquals("LKR", u.getActiveCurrency());
        assertEquals("en", u.getLanguage());
        assertEquals("Asia/Colombo", u.getTimezone());
        assertFalse(u.isOnboarded(), "the guess is provisional until the user confirms it");
    }

    /**
     * No signal, no guess. Denominating someone's ledger in a currency nobody chose
     * is worse than leaving onboarding something to ask for (§0.3).
     */
    @Test
    void register_withoutHints_leavesCurrencyUnset() {
        User u = register(req(null, null));

        assertNull(u.getActiveCurrency());
        assertEquals("en", u.getLanguage());
        assertEquals("UTC", u.getTimezone());
        assertFalse(u.isOnboarded());
    }

    /** A hint is not input to validate — a broken one must not cost somebody their signup. */
    @Test
    void register_withUnusableHints_stillSucceeds() {
        User u = register(req("¯\\_(ツ)_/¯", "Mars/Olympus"));

        assertNull(u.getActiveCurrency());
        assertEquals("UTC", u.getTimezone());
        verify(emailSender).sendVerificationCode("someone@example.com", "123456");
    }
}
