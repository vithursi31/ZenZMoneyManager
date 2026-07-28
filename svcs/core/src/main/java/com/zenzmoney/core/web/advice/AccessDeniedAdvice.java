package com.zenzmoney.core.web.advice;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.web.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
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

    /**
     * Audited because this is where {@code @RolesAllowed} refuses a caller, and method-level
     * annotations are the *only* authorization control here — URL rules are {@code permitAll()} by
     * design. A denial on an endpoint that should have been reachable means a role is wrong; a run of
     * denials on admin paths means someone is trying them.
     */
    private static final Logger audit = AppLog.AUDIT;

    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String path = request.getRequestURI();
        audit.warn("Access denied: {} {} for {}",
                request.getMethod(), path, AuthUtil.currentUsername());
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
