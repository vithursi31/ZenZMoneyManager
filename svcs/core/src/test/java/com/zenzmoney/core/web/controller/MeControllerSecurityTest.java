package com.zenzmoney.core.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /me} and its mutations are role-gated (not in {@code PUBLIC_PATHS}), so an
 * anonymous caller must be refused before reaching any service — see
 * {@link ChatControllerSecurityTest} for why {@code @WithMockUser} does not apply here.
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
}
