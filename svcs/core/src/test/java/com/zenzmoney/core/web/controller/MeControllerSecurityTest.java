package com.zenzmoney.core.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /me} and its mutations are role-gated (not in {@code PUBLIC_PATHS}), so an
 * anonymous caller must be refused before reaching any service — see
 * {@link ChatControllerSecurityTest} for why {@code @WithMockUser} does not apply here.
 *
 * <p>The 403 is written by {@code SecurityConfig}'s access-denied handler rather than by
 * {@code GlobalExceptionHandler}, so it is its own message path and needs its own locale test
 * (F-1.26). "The app is in Sinhala except when it stops working" is exactly the failure this
 * catches.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousCannotReadMe() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotUpdateTheirProfile() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ada\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotChangeThePassword() throws Exception {
        mockMvc.perform(post("/api/v1/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old\",\"newPassword\":\"NewPassw0rd!\"}"))
                .andExpect(status().isForbidden());
    }

    /** The code is the contract and never moves; only the sentence does. */
    @Test
    void accessDenied_isLocalised_butKeepsItsCode() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Accept-Language", "si"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("E1014"))
                .andExpect(jsonPath("$.message").value(not("Access denied")));
    }

    @Test
    void accessDenied_defaultsToEnglish_withNoLanguageHeader() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("E1014"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    /** An attacker-supplied header can only ever select from the allowlist. */
    @Test
    void anUnsupportedLanguageHeaderFallsBackToEnglish() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Accept-Language", "zz-ZZ"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    /**
     * A rejected token is answered by {@code JwtAuthenticationFilter} directly, before the
     * dispatcher has resolved anything — the third and last place a message is written.
     */
    @Test
    void aRejectedToken_isLocalised() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer not-a-real-token")
                        .header("Accept-Language", "si"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("E1061"))
                .andExpect(jsonPath("$.message").value(not("Invalid token")));
    }
}
