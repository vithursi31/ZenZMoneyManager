package com.habit.core.web.controller;

import com.habit.core.web.util.AuthUtil;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardPageController {

    @GetMapping("/dashboard")
    @RolesAllowed({"USER", "ADMIN"})
    public String dashboard(Model model) {
        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("username", AuthUtil.currentUsername());
        return "dashboard";
    }

    @GetMapping("/admin")
    @RolesAllowed("ADMIN")
    public String admin(Model model) {
        model.addAttribute("pageTitle", "Admin");
        return "admin";
    }
}
