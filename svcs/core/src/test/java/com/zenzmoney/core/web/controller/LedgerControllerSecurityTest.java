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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization is method-level here — URL rules are permissive on purpose, so an
 * endpoint without {@code @RolesAllowed} is silently open to anonymous callers.
 * These cover the endpoints added or reshaped by the single-account model, hitting
 * the API directly rather than through any UI.
 *
 * <p>{@code @WithMockUser} does not work against {@code /api/**} in this app — see
 * {@link ChatControllerSecurityTest} for why.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    /** The account read exposes which currency a user operates in; it is not public. */
    @Test
    void anonymousCannotReadTheAccount() throws Exception {
        mockMvc.perform(get("/api/v1/account"))
                .andExpect(status().isForbidden());
    }

    /** The monthly position is the user's whole financial picture in one number. */
    @Test
    void anonymousCannotReadTheMonthlySummary() throws Exception {
        mockMvc.perform(get("/api/v1/summary/monthly"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotOnboard() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"LKR\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * The picker lists are gated exactly like the screen that uses them — a new @GetMapping on a
     * class-level @RolesAllowed controller inherits the gate, and this is what proves it stayed
     * inherited rather than being quietly opened by the method annotation.
     */
    @Test
    void anonymousCannotListTheLanguagePicker() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/languages"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotListTheCurrencyPicker() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/currencies"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotWriteToTheLedger() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"categoryId\":\"c1\",\"amount\":500}"))
                .andExpect(status().isForbidden());
    }

    /**
     * {@code PUBLIC_PATHS} is matched by <em>prefix</em>, so a new route only needs to
     * start with a public one to become anonymous. None of these do; this fails loudly
     * if a future rename drifts one under, say, {@code /api/v1/register…}.
     */
    @Test
    void anonymousCannotListTransactionsOrRecurringTemplates() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/recurring")).andExpect(status().isForbidden());
    }
}
