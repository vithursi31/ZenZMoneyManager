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
import com.zenzmoney.core.i18n.MessageResolver;
import com.zenzmoney.core.i18n.RequestLocale;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Translates typed exceptions into the {@link ApiResponse} envelope once, at the boundary. The HTTP
 * status and the {@code errorCode} both come from the exception's {@code StatusCode}, so neither is
 * spelled out here.
 *
 * <p><b>This is also the only place a user-facing message is rendered</b> (F-1.26). Services throw
 * a message <em>key</em> and never see a locale, so nothing below the web layer decides what a
 * rejection reads like — the same "translate once at the boundary" rule the status codes follow.
 *
 * <p><b>The log stays English and the response does not.</b> Two strings out of one exception, on
 * purpose: {@code audit.log} is kept a year and is read by one developer with grep, so a line in
 * the caller's language is both unreadable and unsearchable.
 *
 * <p><b>This is the only place a failed request is logged.</b> Because services let exceptions
 * propagate instead of catching them, an unlogged handler here means a client gets a 4xx and the
 * server keeps no record of why. Levels follow who is at fault: a client mistake is DEBUG (routine,
 * and noisy at anything higher), an abuse or authorization signal is WARN and also goes to
 * {@code audit.log}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Logger audit = AppLog.AUDIT;

    private final MessageResolver messages;
    private final RequestLocale requestLocale;

    public GlobalExceptionHandler(MessageResolver messages, RequestLocale requestLocale) {
        this.messages = messages;
        this.requestLocale = requestLocale;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        log.debug("404 {}: {}", code(ex), english(ex));
        return respond(ex);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        // An ownership check refused a resource the caller asked for by id. Worth auditing:
        // repeated hits are someone probing for another user's rows.
        log.warn("403 {}: {}", code(ex), english(ex));
        audit.warn("Access forbidden: {}", english(ex));
        return respond(ex);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        // Message only — never the submitted credential or the rejected token.
        log.info("401 {}: {}", code(ex), english(ex));
        return respond(ex);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        log.debug("400 {}: {}", code(ex), english(ex));
        return respond(ex);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyRequests(TooManyRequestsException ex) {
        // A rate limit firing is the abuse signal itself — audited so a burst is reconstructable
        // even though the individual denial is expected behaviour.
        log.warn("429 {}: {} (retryAfter={}s)", code(ex), english(ex), ex.getRetryAfterSeconds());
        audit.warn("Rate limit denied a request: {} (retryAfter={}s)",
                code(ex), ex.getRetryAfterSeconds());
        StatusCode sc = ex.getStatusCode();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(sc.httpStatus());
        if (ex.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        }
        return builder.body(ApiResponse.error(sc, localised(sc)));
    }

    /**
     * Anything carrying a status code that no handler above claimed — today an upstream provider
     * failing (5xx codes), which is expected-but-not-fine and so WARN rather than ERROR.
     */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleService(ServiceException ex) {
        log.warn("{} {}: {}", ex.getStatusCode().httpStatus(), code(ex), english(ex), ex);
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
     * <p>The framework's own text goes to the log as a diagnostic, not to the client: it can name
     * classes, SQL, or config, and it is English regardless of who is asking. The caller gets the
     * code's generic message in their language.
     *
     * <p>Everything else is a defect: logged at ERROR with the stack trace. The correlation id
     * already on the response is what the client quotes in a report.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            if (status.is4xxClientError()) {
                StatusCode sc = codeFor(status).with(ex.getMessage());
                log.debug("{} {}: {}", status.value(), sc.code(), ex.getMessage());
                return ResponseEntity.status(status).body(ApiResponse.error(sc, localised(sc)));
            }
        }
        log.error("500 {} unexpected: {}", StatusCodes.SC_INTERNAL_ERROR.code(), ex.getMessage(), ex);
        StatusCode sc = StatusCodes.SC_INTERNAL_ERROR;
        return ResponseEntity.status(sc.httpStatus()).body(ApiResponse.error(sc, localised(sc)));
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

    /**
     * An unreadable request body — malformed JSON, or a value outside an enum's set such as
     * {@code "paymentMethod": "CHEQUE"}. A client mistake, so a 400: unlike its siblings this
     * exception carries no status of its own (it is not an {@link ErrorResponse}), so without
     * this handler it reaches the catch-all and a typo is reported as a server outage.
     *
     * <p>Jackson's text names Java classes and lists the enum's constants, so it is a log-only
     * diagnostic; the caller gets the code's generic sentence in their language. There is no field
     * list to give them either — deserialization failed, so no binding result exists.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        StatusCode sc = StatusCodes.SC_BAD_REQUEST.with(ex.getMessage());
        log.debug("400 {} unreadable body: {}", sc.code(), ex.getMessage());
        return ResponseEntity.status(sc.httpStatus()).body(ApiResponse.error(sc, localised(sc)));
    }

    /**
     * Field names stay in English — they are contract identifiers the client branches on, and the
     * client already owns the label for its own form field. Only the reason is translated.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors();
        StatusCode sc = StatusCodes.SC_VALIDATION_FAILED;
        log.debug("400 {} validation: {}", sc.code(), join(errors, Locale.ENGLISH));
        return ResponseEntity.status(sc.httpStatus())
                .body(ApiResponse.error(sc, join(errors, requestLocale.resolve())));
    }

    private String join(List<FieldError> errors, Locale locale) {
        return errors.stream()
                .map(e -> e.getField() + ": " + messages.render(e, locale))
                .collect(Collectors.joining(", "));
    }

    private ResponseEntity<ApiResponse<Void>> respond(ServiceException ex) {
        StatusCode sc = ex.getStatusCode();
        return ResponseEntity.status(sc.httpStatus()).body(ApiResponse.error(sc, localised(sc)));
    }

    private String localised(StatusCode sc) {
        return messages.render(sc, requestLocale.resolve());
    }

    /** What the log line carries: the call-site diagnostic if there is one, else English message text. */
    private String english(ServiceException ex) {
        StatusCode sc = ex.getStatusCode();
        return sc.detail() != null ? sc.detail() : messages.render(sc, Locale.ENGLISH);
    }

    private static String code(ServiceException ex) {
        return ex.getStatusCode().code();
    }
}
