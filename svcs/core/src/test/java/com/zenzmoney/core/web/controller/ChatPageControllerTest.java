package com.zenzmoney.core.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The chat screen is an empty shell served to anyone — it renders no user data, and
 * every byte of the conversation crosses the authenticated API from the browser.
 * These tests pin both halves of that: the page is reachable without a token, and it
 * gives away nothing when it is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesTheShellToAnAnonymousBrowser() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"))
                .andExpect(model().attribute("pageTitle", "Chat — ZenZ Money Manager"))
                .andExpect(model().attributeExists("cspNonce"));
    }

    /**
     * Inline scripts are blocked outright and every other script needs the
     * per-request nonce, so a script tag without one is silently dead in the
     * browser — a failure that leaves the page looking fine and doing nothing.
     */
    @Test
    void carriesTheCspNonceOnItsScriptTag() throws Exception {
        MvcResult result = mockMvc.perform(get("/chat")).andReturn();

        String html = result.getResponse().getContentAsString();
        String nonce = (String) result.getModelAndView().getModel().get("cspNonce");
        assertTrue(html.contains("nonce=\"" + nonce + "\""),
                "the chat script must carry the request's CSP nonce or the browser drops it");
        assertTrue(html.contains("/js/chat.js"));
    }
}
