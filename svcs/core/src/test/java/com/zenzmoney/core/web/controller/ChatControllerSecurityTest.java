package com.zenzmoney.core.web.controller;

import com.zenzmoney.core.service.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization here is method-level — URL rules are permissive on purpose, so a
 * missing {@code @RolesAllowed} silently opens an endpoint to anonymous callers.
 * These tests assert the annotation is in force on the chat endpoints, hitting the
 * API directly rather than through any UI.
 *
 * <p><b>Note for future security tests:</b> {@code @WithMockUser} does not work
 * against {@code /api/**} in this app. {@link com.zenzmoney.core.web.filter.JwtAuthenticationFilter}
 * replaces the {@code SecurityContext} with an anonymous token whenever the request
 * carries no {@code Bearer} header, discarding whatever the test set. Authenticate
 * by minting a real access token, as below — which has the advantage of exercising
 * the filter instead of bypassing it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerSecurityTest {

    private static final String MESSAGE_BODY = "{\"message\":\"spent 5 on lunch\"}";
    private static final String ACTION_BODY = "{\"messageId\":\"some-id\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void anonymousCannotSendAChatMessage() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MESSAGE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotConfirmADraft() throws Exception {
        mockMvc.perform(post("/api/v1/chat/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotAmendADraft() throws Exception {
        mockMvc.perform(post("/api/v1/chat/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"some-id\",\"categoryId\":\"c1\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Undo deletes a ledger row, so it is the most damaging chat endpoint to leave
     * open — and the newest, which is exactly when the annotation gets forgotten.
     */
    @Test
    void anonymousCannotUndoAWrite() throws Exception {
        mockMvc.perform(post("/api/v1/chat/undo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUndoWithNoMessageIdIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/chat/undo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRefreshTokenCannotBeUsedToUndoAWrite() throws Exception {
        String refresh = jwtTokenService.generateRefreshToken("ghost@example.com");

        mockMvc.perform(post("/api/v1/chat/undo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotRejectADraft() throws Exception {
        mockMvc.perform(post("/api/v1/chat/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTION_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotReadConversationHistory() throws Exception {
        mockMvc.perform(get("/api/v1/chat").param("sessionId", "s1"))
                .andExpect(status().isForbidden());
    }

    /**
     * A well-formed token is not enough — the subject must resolve to a real user
     * row, so a token naming an account that doesn't exist authenticates nobody.
     */
    @Test
    void aTokenForAnAccountThatDoesNotExistIsRejected() throws Exception {
        String token = jwtTokenService.generateAccessToken("ghost@example.com");

        mockMvc.perform(post("/api/v1/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MESSAGE_BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A refresh token must not act as an access token — the same rule the rest of
     * the API relies on, asserted on a new endpoint rather than assumed.
     */
    @Test
    void aRefreshTokenCannotBeUsedToSendAChatMessage() throws Exception {
        String refresh = jwtTokenService.generateRefreshToken("ghost@example.com");

        mockMvc.perform(post("/api/v1/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MESSAGE_BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Bean validation runs during argument resolution, before the method-security
     * proxy — so an over-long or blank message is rejected without any model call.
     */
    @Test
    void aBlankMessageIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The draft endpoint takes money, so the amount is bounded at the seam. A
     * zero or negative amount is refused here rather than reaching a draft the
     * user could then confirm into the ledger.
     */
    @Test
    void aNonPositiveDraftAmountIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/chat/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"some-id\",\"amountMinor\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDraftEditWithNoMessageIdIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/chat/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":500}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOverlongMessageIsRejectedByValidation() throws Exception {
        String tooLong = "a".repeat(501);

        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
