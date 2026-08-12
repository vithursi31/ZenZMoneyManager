package com.zenzmoney.core.web.controller;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.service.CurrentUserService;
import com.zenzmoney.core.web.dto.MeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@code /me} is what a client routes on after login — onboarding screen or
 * dashboard — so both states have to survive serialisation. Role gating for the
 * endpoint is covered in {@link ChatControllerSecurityTest} style elsewhere; what
 * is new here is the body.
 */
@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    @Mock CurrentUserService currentUser;
    @InjectMocks MeController meController;

    private MeResponse me() {
        ResponseEntity<ApiResponse<MeResponse>> resp = meController.me();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        return resp.getBody().getData();
    }

    @Test
    void me_reportsConfirmedPreferences() {
        User u = new User();
        u.setEmail("someone@example.com");
        u.setActiveCurrency("LKR");
        u.setLanguage("ta");
        u.setTimezone("Asia/Colombo");
        u.setOnboarded(true);
        when(currentUser.requireUser()).thenReturn(u);

        MeResponse body = me();

        assertEquals("someone@example.com", body.getEmail());
        assertEquals("LKR", body.getActiveCurrency());
        assertEquals("ta", body.getLanguage());
        assertEquals("Asia/Colombo", body.getTimezone());
        assertTrue(body.isOnboarded());
        assertTrue(body.isAuthenticated());
    }

    /**
     * The case the endpoint exists for, and the one that used to be impossible to
     * return: the previous {@code Map.of} body throws on a null value, so a user who
     * registered without a usable locale would have crashed the very call meant to
     * tell the client to onboard them.
     */
    @Test
    void me_reportsAnUnonboardedUser_withoutChokingOnNulls() {
        User u = new User();
        u.setEmail("new@example.com");
        when(currentUser.requireUser()).thenReturn(u);

        MeResponse body = me();

        assertEquals("new@example.com", body.getEmail());
        assertNull(body.getActiveCurrency());
        assertNull(body.getLanguage());
        assertFalse(body.isOnboarded());
    }
}
