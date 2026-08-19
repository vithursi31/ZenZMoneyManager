package com.zenzmoney.core.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The chat capture screen (F-1.11) — transcript, suggestion chips, and the
 * transaction preview the user confirms from.
 *
 * <p><b>Deliberately not {@code @RolesAllowed}, and deliberately renders nothing
 * user-specific.</b> The page is an empty shell: every byte of the user's data
 * crosses the authenticated API from the browser, exactly as it does for a mobile
 * client. Gating the shell would only make it unreachable — {@code formLogin} is
 * disabled and there is no browser session login, so the page signs in against
 * {@code POST /api/v1/authenticate} and holds the access token for the tab.
 */
@Controller
public class ChatPageController {

    @GetMapping("/chat")
    public String chat(Model model) {
        model.addAttribute("pageTitle", "Chat — ZenZ Money Manager");
        return "chat";
    }
}
