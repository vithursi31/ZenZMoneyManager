package com.zenzmoney.core.web.advice;

import com.zenzmoney.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The catch-all must not turn a client mistake into a server error.
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
}
