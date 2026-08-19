package com.zenzmoney.core.service;

import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.web.dto.MeResponse;
import com.zenzmoney.core.web.dto.UpdateProfileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock UserRepository userRepository;
    @Mock CurrentUserService currentUser;
    @InjectMocks ProfileService profileService;

    private UpdateProfileRequest req(String firstName, String lastName) {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setFirstName(firstName);
        r.setLastName(lastName);
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
}
