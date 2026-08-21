package com.zenzmoney.core.i18n;

import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.SupportedLanguages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * The precedence rule, which is the whole point of the class: what the user chose beats what their
 * device happens to be set to, and the header is what we fall back to when there is no user.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestLocaleTest {

    private static final Locale SINHALA = Locale.forLanguageTag("si");

    @Mock UserRepository userRepository;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    private RequestLocale requestLocale() {
        return new RequestLocale(userRepository, new SupportedLanguages("en,si"));
    }

    private void signedInWithLanguage(String language) {
        User user = new User();
        user.setEmail("jane@example.com");
        user.setLanguage(language);
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("jane@example.com", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void storedPreferenceBeatsTheHeader() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        signedInWithLanguage("si");

        assertEquals(SINHALA, requestLocale().resolve());
    }

    @Test
    void storedPreferenceIsMatchedLeniently() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        signedInWithLanguage("si-LK");

        assertEquals(SINHALA, requestLocale().resolve());
    }

    @Test
    void anonymousFallsBackToTheHeader() {
        LocaleContextHolder.setLocale(SINHALA);

        assertEquals(SINHALA, requestLocale().resolve());
    }

    /** A language we cannot serve is not a preference — the header decides instead. */
    @Test
    void unsupportedStoredLanguageFallsBackToTheHeader() {
        LocaleContextHolder.setLocale(SINHALA);
        signedInWithLanguage("fr");

        assertEquals(SINHALA, requestLocale().resolve());
    }

    @Test
    void noStoredLanguageFallsBackToTheHeader() {
        LocaleContextHolder.setLocale(SINHALA);
        signedInWithLanguage(null);

        assertEquals(SINHALA, requestLocale().resolve());
    }

    /** Resolving a message must never be the thing that fails a request that was already failing. */
    @Test
    void aBrokenLookupDoesNotThrow() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("jane@example.com", "x",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(userRepository.findByEmail("jane@example.com"))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        assertEquals(Locale.ENGLISH, requestLocale().resolve());
    }
}
