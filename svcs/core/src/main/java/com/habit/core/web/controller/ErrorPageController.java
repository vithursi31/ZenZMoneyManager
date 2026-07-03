package com.habit.core.web.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class ErrorPageController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr == null ? 500 : Integer.parseInt(statusAttr.toString());

        return switch (status) {
            case 403 -> { model.addAttribute("pageTitle", "Access denied"); yield "error/403"; }
            case 404 -> { model.addAttribute("pageTitle", "Not found"); yield "error/404"; }
            default  -> { model.addAttribute("pageTitle", "Server error"); yield "error/500"; }
        };
    }

    @GetMapping("/error/403")
    public String forbidden(Model model) {
        model.addAttribute("pageTitle", "Access denied");
        return "error/403";
    }

    @GetMapping("/error/404")
    public String notFound(Model model) {
        model.addAttribute("pageTitle", "Not found");
        return "error/404";
    }

    @GetMapping("/error/500")
    public String serverError(Model model) {
        model.addAttribute("pageTitle", "Server error");
        return "error/500";
    }
}
