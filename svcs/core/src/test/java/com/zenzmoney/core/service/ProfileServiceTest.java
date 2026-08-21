package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.SupportedLanguages;
import com.zenzmoney.core.web.dto.MeResponse;
import com.zenzmoney.core.web.dto.UpdateProfileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock UserRepository userRepository;
    @Mock CurrentUserService currentUser;
    // A real one, not a mock: the allowlist is behaviour under test, not a collaborator.
    @Spy SupportedLanguages supportedLanguages = new SupportedLanguages("en,si");
    @InjectMocks ProfileService profileService;

    private UpdateProfileRequest req(String firstName, String lastName) {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setFirstName(firstName);
        r.setLastName(lastName);
        return r;
    }

    private UpdateProfileRequest language(String language) {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setLanguage(language);
        return r;
    }

    @Test
    void updateProfile_setsBothNames() {
        User u = new User();
        u.setEmail("someone@example.com");
        when(currentUser.requireUser()).thenReturn(u);

        MeResponse resp = profileService.updateProfile(req("Ada", "Lovelace"));

        assertEquals("Ada", u.getFirstName());
        assertEquals("Lovelace", u.getLastName());
        assertEquals("Ada", resp.getFirstName());
        assertEquals("Lovelace", resp.getLastName());
        verify(userRepository).save(u);
    }

    @Test
    void updateProfile_leavesANameAlone_whenNotSupplied() {
        User u = new User();
        u.setFirstName("Ada");
        u.setLastName("Lovelace");
        when(currentUser.requireUser()).thenReturn(u);

        profileService.updateProfile(req(null, "Byron"));

        assertEquals("Ada", u.getFirstName());
        assertEquals("Byron", u.getLastName());
    }

    @Test
    void updateProfile_ignoresBlankValues() {
        User u = new User();
        u.setFirstName("Ada");
        when(currentUser.requireUser()).thenReturn(u);

        profileService.updateProfile(req("  ", "Lovelace"));

        assertEquals("Ada", u.getFirstName());
        assertEquals("Lovelace", u.getLastName());
    }

    /**
     * Onboarding sets the language once; this is the only way to change it afterwards (F-1.26).
     */
    @Test
    void updateProfile_changesTheLanguage() {
        User u = new User();
        u.setLanguage("en");
        when(currentUser.requireUser()).thenReturn(u);

        MeResponse resp = profileService.updateProfile(language("si"));

        assertEquals("si", u.getLanguage());
        assertEquals("si", resp.getLanguage());
        verify(userRepository).save(u);
    }

    /** A stored tag has to be one the resolver can match, so the region is dropped on the way in. */
    @Test
    void updateProfile_normalisesTheStoredTag() {
        User u = new User();
        when(currentUser.requireUser()).thenReturn(u);

        profileService.updateProfile(language("si-LK"));

        assertEquals("si", u.getLanguage());
    }

    /**
     * Storing a language with no bundle behind it would leave every message in English with no
     * explanation, so it is refused rather than accepted and quietly ignored.
     */
    @Test
    void updateProfile_refusesALanguageTheServerCannotAnswerIn() {
        User u = new User();
        u.setLanguage("en");
        when(currentUser.requireUser()).thenReturn(u);

        assertThrows(BadRequestException.class, () -> profileService.updateProfile(language("fr")));

        assertEquals("en", u.getLanguage());
        verify(userRepository, never()).save(u);
    }

    @Test
    void updateProfile_leavesTheLanguageAlone_whenNotSupplied() {
        User u = new User();
        u.setLanguage("si");
        when(currentUser.requireUser()).thenReturn(u);

        profileService.updateProfile(req("Ada", null));

        assertEquals("si", u.getLanguage());
    }
}
