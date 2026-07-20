package com.zenzmoney.core.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MarketingPageController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("pageTitle", "ZenZ Money Manager");
        return "home";
    }

//    @GetMapping("/login")
//    public String login(Model model) {
//        model.addAttribute("pageTitle", "Login — ZenZ Money Manager");
//        return "login";
//    }
//
//    @GetMapping("/register")
//    public String register(Model model) {
//        model.addAttribute("pageTitle", "Register — ZenZ Money Manager");
//        return "register";
//    }
}
