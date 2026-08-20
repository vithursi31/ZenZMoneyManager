package com.zenzmoney.core.web.advice;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.ForbiddenException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Translates typed exceptions into the {@link ApiResponse} envelope once, at the boundary. The HTTP
 * status and the {@code errorCode} both come from the exception's {@code StatusCode}, so neither is
 * spelled out here.
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
        log.debug("404 {}: {}", code(ex), ex.getMessage());
        return respond(ex);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        // An ownership check refused a resource the caller asked for by id. Worth auditing:
        // repeated hits are someone probing for another user's rows.
        log.warn("403 {}: {}", code(ex), ex.getMessage());
        audit.warn("Access forbidden: {}", ex.getMessage());
        return respond(ex);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        // Message only — never the submitted credential or the rejected token.
        log.info("401 {}: {}", code(ex), ex.getMessage());
        return respond(ex);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        log.debug("400 {}: {}", code(ex), ex.getMessage());
        return respond(ex);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyRequests(TooManyRequestsException ex) {
        // A rate limit firing is the abuse signal itself — audited so a burst is reconstructable
        // even though the individual denial is expected behaviour.
        log.warn("429 {}: {} (retryAfter={}s)", code(ex), ex.getMessage(), ex.getRetryAfterSeconds());
        audit.warn("Rate limit denied a request: {} (retryAfter={}s)",
                code(ex), ex.getRetryAfterSeconds());
        StatusCode sc = ex.getStatusCode();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(sc.httpStatus());
        if (ex.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return builder.body(ApiResponse.error(sc));
    }

    /**
     * Anything carrying a status code that no handler above claimed — today an upstream provider
     * failing (5xx codes), which is expected-but-not-fine and so WARN rather than ERROR.
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleService(ServiceException ex) {
        log.warn("{} {}: {}", ex.getStatusCode().httpStatus(), code(ex), ex.getMessage(), ex);
        return respond(ex);
    }

    /**
     * The catch-all, for anything no typed handler above claimed.
     *
     * <p>Spring's own MVC failures are separated out first. An unknown path, a wrong verb, a
     * malformed JSON body, or a mistyped query param is a <em>client</em> mistake that already
     * carries the right 4xx status via {@link ErrorResponse} — reporting those as 500 hides a typo
     * as a server outage and wakes someone at 2am for it. The framework's status is kept, since it
     * is more specific than the status on the code we label it with.
     *
     * <p>Everything else is a defect: logged at ERROR with the stack trace, and answered with the
     * code's own generic message rather than {@code ex.getMessage()}, which can name classes, SQL,
     * or config. The correlation id already on the response is what the client quotes in a report.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            if (status.is4xxClientError()) {
                StatusCode sc = codeFor(status).with(ex.getMessage());
                log.debug("{} {}: {}", status.value(), sc.code(), ex.getMessage());
                return ResponseEntity.status(status).body(ApiResponse.error(sc));
            }
        }
        log.error("500 {} unexpected: {}", StatusCodes.SC_INTERNAL_ERROR.code(), ex.getMessage(), ex);
        return ResponseEntity.status(StatusCodes.SC_INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(StatusCodes.SC_INTERNAL_ERROR));
    }

    /** Reuses the existing codes so a client's error handling doesn't grow a second vocabulary. */
    private static StatusCode codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return StatusCodes.SC_NOT_FOUND;
        }
        if (status.value() == HttpStatus.FORBIDDEN.value()) {
            return StatusCodes.SC_NOT_AUTHORIZED;
        }
        return StatusCodes.SC_BAD_REQUEST;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        StatusCode sc = StatusCodes.SC_VALIDATION_FAILED.with(message);
        log.debug("400 {} validation: {}", sc.code(), message);
        return ResponseEntity.status(sc.httpStatus()).body(ApiResponse.error(sc));
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ServiceException ex) {
        StatusCode sc = ex.getStatusCode();
        return ResponseEntity.status(sc.httpStatus()).body(ApiResponse.error(sc));
    }

    private static String code(ServiceException ex) {
        return ex.getStatusCode().code();
    }
}
