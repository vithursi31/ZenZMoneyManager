package com.zenzmoney.core.web.advice;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.ForbiddenException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Translates typed exceptions into the {@link ApiResponse} envelope once, at the boundary.
 *
 * <p><b>This is the only place a failed request is logged.</b> Because services let exceptions
 * propagate instead of catching them, an unlogged handler here means a client gets a 4xx and the
 * server keeps no record of why — the failure is invisible in {@code debug.log}. Levels follow who
 * is at fault: a client mistake is DEBUG (routine, and noisy at anything higher), an abuse or
 * authorization signal is WARN and also goes to {@code audit.log}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Logger audit = AppLog.AUDIT;

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        log.debug("404 E1010: {}", ex.getMessage());
        return ResponseEntity.status(404).body(ApiResponse.error("E1010", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        // An ownership check refused a resource the caller asked for by id. Worth auditing:
        // repeated hits are someone probing for another user's rows.
        log.warn("403 E1014: {}", ex.getMessage());
        audit.warn("Access forbidden: {}", ex.getMessage());
        return ResponseEntity.status(403).body(ApiResponse.error("E1014", ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        // Message only — never the submitted credential or the rejected token.
        log.info("401 {}: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(401).body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        log.debug("400 E1013: {}", ex.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error("E1013", ex.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyRequests(TooManyRequestsException ex) {
        // A rate limit firing is the abuse signal itself — audited so a burst is reconstructable
        // even though the individual denial is expected behaviour.
        log.warn("429 {}: {} (retryAfter={}s)",
                ex.getErrorCode(), ex.getMessage(), ex.getRetryAfterSeconds());
        audit.warn("Rate limit denied a request: {} (retryAfter={}s)",
                ex.getErrorCode(), ex.getRetryAfterSeconds());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (ex.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return builder.body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.debug("400 E1015 validation: {}", message);
        return ResponseEntity.status(400).body(ApiResponse.error("E1015", message));
    }
}
