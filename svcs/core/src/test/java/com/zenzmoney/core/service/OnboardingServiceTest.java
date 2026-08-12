package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.Account;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.dto.CategoryResponse;
import com.zenzmoney.core.web.dto.OnboardingRequest;
import com.zenzmoney.core.web.dto.OnboardingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock UserRepository userRepository;
    @Mock AccountService accountService;
    @Mock CategoryService categoryService;
    @Mock CurrentUserService currentUser;
    @InjectMocks OnboardingService onboardingService;

    /** A user still carrying whatever registration guessed for them (F-1.27). */
    private User user(String activeCurrency) {
        User u = new User();
        u.setId("u1");
        u.setActiveCurrency(activeCurrency);
        return u;
    }

    /** A user who has already been through onboarding, so their currency is frozen. */
    private User onboardedUser(String activeCurrency) {
        User u = user(activeCurrency);
        u.setOnboarded(true);
        return u;
    }

    private OnboardingRequest req(String currency, String language, String timezone) {
        OnboardingRequest r = new OnboardingRequest();
        r.setCurrency(currency);
        r.setLanguage(language);
        r.setTimezone(timezone);
        return r;
    }

    private void stubProvisioning() {
        Account a = new Account();
        a.setId("a1");
        when(accountService.provision(any())).thenReturn(a);
        when(categoryService.seedDefaults()).thenReturn(List.<CategoryResponse>of());
    }

    @Test
    void complete_setsPreferences_andProvisionsAccountAndCategories() {
        User u = user(null);
        when(currentUser.requireUser()).thenReturn(u);
        stubProvisioning();

        OnboardingResponse resp = onboardingService.complete(req("lkr", "ta", "Asia/Colombo"));

        assertEquals("LKR", u.getActiveCurrency());   // normalized to upper case
        assertEquals("ta", u.getLanguage());
        assertEquals("Asia/Colombo", u.getTimezone());
        assertEquals("a1", resp.getAccountId());
        assertTrue(u.isOnboarded(), "the currency is confirmed and now frozen");
        verify(userRepository).save(u);
        verify(accountService).provision(u);
        verify(categoryService).seedDefaults();
    }

    /** Re-running onboarding must not mint a second account or duplicate categories. */
    @Test
    void complete_isIdempotent_forTheSameCurrency() {
        User u = onboardedUser("LKR");
        when(currentUser.requireUser()).thenReturn(u);
        stubProvisioning();

        onboardingService.complete(req("LKR", null, null));

        assertEquals("LKR", u.getActiveCurrency());
        verify(accountService).provision(u);   // get-or-create, not create
    }

    /**
     * The whole point of this screen for a user whose currency was guessed from their
     * signup locale: it stays correctable right up until they confirm one.
     */
    @Test
    void complete_replacesAProvisionalCurrency() {
        User u = user("USD");   // guessed at registration, never confirmed
        when(currentUser.requireUser()).thenReturn(u);
        stubProvisioning();

        onboardingService.complete(req("LKR", null, null));

        assertEquals("LKR", u.getActiveCurrency());
        assertTrue(u.isOnboarded());
        // Lazy provisioning means the account may already exist in the guess; it moves too.
        verify(accountService).redenominate(u, "LKR");
    }

    /**
     * Once confirmed, amounts are minor units denominated in that currency; switching
     * without a conversion policy would silently reinterpret every stored figure (§0.3).
     */
    @Test
    void complete_changingAConfirmedCurrency_rejected() {
        when(currentUser.requireUser()).thenReturn(onboardedUser("LKR"));

        assertThrows(BadRequestException.class, () -> onboardingService.complete(req("USD", null, null)));
        verify(accountService, never()).redenominate(any(), any());
        verify(accountService, never()).provision(any());
    }

    @Test
    void complete_unknownCurrency_rejected() {
        when(currentUser.requireUser()).thenReturn(user(null));

        assertThrows(BadRequestException.class, () -> onboardingService.complete(req("XYZ", null, null)));
        verify(userRepository, never()).save(any());
    }

    /** The timezone decides where the user's months begin, so a bad one cannot be stored. */
    @Test
    void complete_unknownTimezone_rejected() {
        when(currentUser.requireUser()).thenReturn(user(null));

        assertThrows(BadRequestException.class,
                () -> onboardingService.complete(req("LKR", "en", "Mars/Olympus")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void complete_leavesTimezoneAlone_whenNotSupplied() {
        User u = user(null);
        u.setTimezone("Asia/Colombo");
        when(currentUser.requireUser()).thenReturn(u);
        stubProvisioning();

        onboardingService.complete(req("LKR", "en", null));

        assertEquals("Asia/Colombo", u.getTimezone());
    }
}
