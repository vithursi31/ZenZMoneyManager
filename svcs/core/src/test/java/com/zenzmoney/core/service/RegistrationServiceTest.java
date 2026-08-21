package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.SupportedLanguages;
import com.zenzmoney.core.web.dto.RegisterRequest;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
    // A real one, not a mock: the allowlist is behaviour under test, not a collaborator.
    @Spy SupportedLanguages supportedLanguages = new SupportedLanguages("en,si");
    @InjectMocks RegistrationService registrationService;

    private RegisterRequest req(String locale, String timezone) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail("someone@example.com");
        r.setPassword("Passw0rd!");
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
        assertEquals("si", u.getLanguage());
        assertEquals("Asia/Colombo", u.getTimezone());
        assertFalse(u.isOnboarded(), "the guess is provisional until the user confirms it");
    }

    /**
     * The seeded language is what the verification email is written in, and that mail goes out
     * before the user has ever seen the picker (F-1.26).
     */
    @Test
    void register_sendsTheVerificationCode_inTheSeededLanguage() {
        register(req("si-LK", "Asia/Colombo"));

        verify(emailSender).sendVerificationCode("someone@example.com", "123456",
                Locale.forLanguageTag("si"));
    }

    /** A language the server has no bundle for is not a preference — English is. */
    @Test
    void register_fallsBackToEnglish_whenTheLocaleNamesAnUnsupportedLanguage() {
        User u = register(req("fr-FR", null));

        assertEquals("en", u.getLanguage());
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
        verify(emailSender).sendVerificationCode("someone@example.com", "123456", Locale.ENGLISH);
    }

    /** Disposable-domain signups are refused before any account row is created. */
    @Test
    void register_withDisposableEmailDomain_isRejected() {
        RegisterRequest r = req(null, null);
        r.setEmail("someone@mailinator.com");

        assertThrows(BadRequestException.class, () -> registrationService.register(r));

        verify(userRepository, never()).save(any());
        verify(emailSender, never()).sendVerificationCode(anyString(), anyString(), any());
    }
}
