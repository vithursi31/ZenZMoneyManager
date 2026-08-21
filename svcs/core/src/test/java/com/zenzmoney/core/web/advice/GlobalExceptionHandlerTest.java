package com.zenzmoney.core.web.advice;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.ForbiddenException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Every failure answers with the status and the code carried by its {@code StatusCode} — the handler
 * spells out neither, so these cases are what pins the wire contract down.
 *
 * <p>The catch-all must not turn a client mistake into a server error.
 *
 * <p>An unknown path is the live example: {@code /api/v1/service-status} is listed in the filter's
 * PUBLIC_PATHS but has no controller, so it is a 404 route. A blanket
 * {@code @ExceptionHandler(Exception.class)} answered it {@code 500}, which reads as an outage.
 *
 * <p>They also pin the message rule (F-1.26): the body carries the caller's language, and a
 * call-site diagnostic never reaches it.
 */
class GlobalExceptionHandlerTest {

    private static final Locale SINHALA = Locale.forLanguageTag("si");

    private final GlobalExceptionHandler handler = handlerFor(Locale.ENGLISH);

    private static GlobalExceptionHandler handlerFor(Locale locale) {
        return new GlobalExceptionHandler(TestMessages.resolver(), TestMessages.fixedLocale(locale));
    }

    @Test
    void unknownPath_keeps404_notReportedAsServerError() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(
                new NoResourceFoundException(HttpMethod.GET, "/api/v1/service-status"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("E1010", response.getBody().getErrorCode());
    }

    @Test
    void wrongHttpMethod_keepsItsOwn4xx() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertEquals(405, response.getStatusCode().value());
        assertEquals("E1013", response.getBody().getErrorCode());
    }

    /** A genuine defect is a 500 — and its message is logged, never returned. */
    @Test
    void unexpectedFailure_is500_andLeaksNothing() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(
                new IllegalStateException("jdbc:postgresql://localhost:5454/zenzmoney refused"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("E1000", response.getBody().getErrorCode());
        assertEquals("An unexpected error occurred.", response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().contains("jdbc"),
                "internal detail must not reach the client");
    }

    /**
     * A bare string is a <em>diagnostic</em>, for the log. It is English and can name internals, so
     * the client gets the code's own generic message instead.
     */
    @Test
    void notFound_withADiagnostic_answersTheGenericMessage_notTheDiagnostic() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNotFound(new NotFoundException("No transaction with id 9f3c-…"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("E1010", response.getBody().getErrorCode());
        assertEquals("Not found", response.getBody().getMessage());
    }

    @Test
    void badRequest_withAMessageKey_answersThatKeysText() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadRequest(new BadRequestException(Msg.EMAIL_IN_USE));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("E1013", response.getBody().getErrorCode());
        assertEquals("Email already in use", response.getBody().getMessage());
    }

    /** The placeholder is a MessageFormat pattern, so a stray apostrophe in the bundle shows up here. */
    @Test
    void messageArguments_areInterpolated_withTheQuotesIntact() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadRequest(new BadRequestException(Msg.CATEGORY_DUPLICATE, "Groceries"));

        assertEquals("A category named 'Groceries' already exists.", response.getBody().getMessage());
    }

    /** The code is the contract and does not move; only the sentence does. */
    @Test
    void sameRejection_inSinhala_keepsTheCode_andChangesTheMessage() {
        ResponseEntity<ApiResponse<Void>> english =
                handler.handleBadRequest(new BadRequestException(Msg.EMAIL_IN_USE));
        ResponseEntity<ApiResponse<Void>> sinhala = handlerFor(SINHALA)
                .handleBadRequest(new BadRequestException(Msg.EMAIL_IN_USE));

        assertEquals(english.getBody().getErrorCode(), sinhala.getBody().getErrorCode());
        assertNotEquals(english.getBody().getMessage(), sinhala.getBody().getMessage(),
                "the Sinhala bundle must actually carry this key");
    }

    /** A language with no bundle of its own falls back to English, never to a raw key. */
    @Test
    void unsupportedLanguage_fallsBackToEnglish() {
        // Dutch: deliberately a language the app ships no bundle for.
        ResponseEntity<ApiResponse<Void>> response = handlerFor(Locale.forLanguageTag("nl"))
                .handleBadRequest(new BadRequestException(Msg.EMAIL_IN_USE));

        assertEquals("Email already in use", response.getBody().getMessage());
    }

    @Test
    void forbidden_is403_withTheNotAuthorizedCode() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleForbidden(new ForbiddenException("Not your account"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals("E1014", response.getBody().getErrorCode());
    }

    /** A 401 carries the reason as a code, so a client can tell "refresh me" from "sign in again". */
    @Test
    void unauthorized_is401_withTheCodeItWasGiven() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnauthorized(
                new UnauthorizedException(ServiceCodes.SC_TOKEN_EXPIRED));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("E1062", response.getBody().getErrorCode());
        assertEquals(ServiceCodes.SC_TOKEN_EXPIRED.description(), response.getBody().getMessage());
    }

    @Test
    void rateLimited_is429_withRetryAfter() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleTooManyRequests(
                new TooManyRequestsException(ServiceCodes.SC_OTP_RATE_LIMIT_EXCEEDED, 42));

        assertEquals(429, response.getStatusCode().value());
        assertEquals("E1051", response.getBody().getErrorCode());
        assertEquals("42", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    /** An upstream provider failing is a 502 on our side, not a 401 blamed on the caller. */
    @Test
    void providerFailure_is502_notAnAuthenticationFailure() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleService(
                new ServiceException(ServiceCodes.SC_APPLE_CONNECTOR_ERROR.with("Apple keys missing")));

        assertEquals(502, response.getStatusCode().value());
        assertEquals("E1305", response.getBody().getErrorCode());
    }

    @Test
    void providerNotConfigured_is503() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleService(
                new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED));

        assertEquals(503, response.getStatusCode().value());
        assertEquals("E1005", response.getBody().getErrorCode());
    }
}
