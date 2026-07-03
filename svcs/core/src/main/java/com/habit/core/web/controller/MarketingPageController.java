package com.habit.core.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MarketingPageController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("pageTitle", "Habit");
        return "home";
    }

//    @GetMapping("/login")
//    public String login(Model model) {
//        model.addAttribute("pageTitle", "Login — Habit");
//        return "login";
//    }
//
//    @GetMapping("/register")
//    public String register(Model model) {
//        model.addAttribute("pageTitle", "Register — Habit");
//        return "register";
//    }
}
