package com.zenzmoney.core.web.advice;

import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.ForbiddenException;
import com.zenzmoney.common.exception.NotFoundException;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every failure answers with the status and the code carried by its {@code StatusCode} — the handler
 * spells out neither, so these cases are what pins the wire contract down.
 *
 * <p>The catch-all must not turn a client mistake into a server error.
 *
 * <p>An unknown path is the live example: {@code /api/v1/service-status} is listed in the filter's
 * PUBLIC_PATHS but has no controller, so it is a 404 route. A blanket
 * {@code @ExceptionHandler(Exception.class)} answered it {@code 500}, which reads as an outage.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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

    @Test
    void notFound_is404_withTheNotFoundCode_andKeepsTheCallSiteMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNotFound(new NotFoundException("No transaction with that id"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("E1010", response.getBody().getErrorCode());
        assertEquals("No transaction with that id", response.getBody().getMessage());
    }

    @Test
    void badRequest_is400_withTheBadRequestCode() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadRequest(new BadRequestException("Email already in use"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("E1013", response.getBody().getErrorCode());
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
