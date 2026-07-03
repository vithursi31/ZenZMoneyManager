package com.habit.core.web.advice;

import com.habit.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessDeniedAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("E1014", "Access denied"));
        }
        ModelAndView mav = new ModelAndView("error/403");
        mav.addObject("pageTitle", "Access denied");
        mav.setStatus(HttpStatus.FORBIDDEN);
        return mav;
    }
}
