package com.zenzmoney.core.service.oauth;

import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: every one of these used to leak {@code WebClientResponseException} out of the
 * connectors. No handler in {@code GlobalExceptionHandler} claims it and it is not an
 * {@code ErrorResponse}, so it hit the catch-all — an expired token answered 500 with a stack
 * trace instead of 401, and a provider outage looked identical to a defect in this codebase.
 */
class ProviderCallTest {

    private static WebClientResponseException httpError(int status, String body) {
        return WebClientResponseException.create(
                status, "status " + status, HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void providerReturns2xx_valuePassesThrough() {
        String out = ProviderCall.await(() -> "ok", ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google tokeninfo");
        assertEquals("ok", out);
    }

    /** An empty Mono blocks to null; the connectors check for it themselves. */
    @Test
    void providerReturnsNoBody_nullPassesThrough() {
        assertNull(ProviderCall.await(() -> null, ServiceCodes.SC_APPLE_CONNECTOR_ERROR, "Apple keys"));
    }

    @Test
    void provider4xx_isTheCallersBadToken_notAServerError() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> ProviderCall.await(() -> { throw httpError(400, "{\"error\":\"invalid_token\"}"); },
                        ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google tokeninfo"));

        assertEquals("E1071", ex.getStatusCode().code());
        assertEquals(401, ex.getStatusCode().httpStatus());
    }

    /**
     * The provider's machine code is what separates a stale token from a misconfigured secret,
     * so it belongs in the diagnostic — which is log-only and never reaches the client.
     */
    @Test
    void provider4xx_keepsTheProviderErrorCodeInTheDiagnostic() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> ProviderCall.await(() -> { throw httpError(400, "{\"error\":\"invalid_grant\"}"); },
                        ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google token exchange"));

        String diagnostic = ex.getStatusCode().detail();
        assertTrue(diagnostic.contains("Google token exchange"), diagnostic);
        assertTrue(diagnostic.contains("invalid_grant"), diagnostic);
    }

    /**
     * A wrong client secret fails every sign-in through that path. Reporting it as the caller's
     * bad token points the whole investigation at the user instead of at the config.
     */
    @Test
    void providerRefusesOurOwnCredentials_isAConfigProblemNotABadToken() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ProviderCall.await(() -> { throw httpError(401, "{\"error\":\"invalid_client\"}"); },
                        ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google token exchange"));

        assertEquals("E1005", ex.getStatusCode().code());
        assertEquals(503, ex.getStatusCode().httpStatus());
    }

    @Test
    void providerRefusesOurGrantType_isAlsoAConfigProblem() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ProviderCall.await(() -> { throw httpError(400, "{\"error\":\"unauthorized_client\"}"); },
                        ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR, "Facebook token exchange"));

        assertEquals("E1005", ex.getStatusCode().code());
    }

    @Test
    void provider5xx_isTheProvidersFault_andCarriesThatProvidersCode() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ProviderCall.await(() -> { throw httpError(503, "upstream down"); },
                        ServiceCodes.SC_APPLE_CONNECTOR_ERROR, "Apple keys"));

        assertEquals("E1305", ex.getStatusCode().code());
        assertEquals(502, ex.getStatusCode().httpStatus());
    }

    /** Connect refused, DNS failure, and the response timeout all arrive as this. */
    @Test
    void providerUnreachable_isTheProvidersFault() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ProviderCall.await(() -> {
                    throw new WebClientRequestException(new IOException("connection refused"),
                            HttpMethod.GET, URI.create("https://appleid.apple.com/auth/keys"),
                            HttpHeaders.EMPTY);
                }, ServiceCodes.SC_APPLE_CONNECTOR_ERROR, "Apple keys"));

        assertEquals("E1305", ex.getStatusCode().code());
        assertEquals(502, ex.getStatusCode().httpStatus());
    }

    @Test
    void provider4xxWithNoBody_stillTranslates() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> ProviderCall.await(() -> { throw httpError(401, ""); },
                        ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR, "Facebook /me"));
        assertEquals("E1071", ex.getStatusCode().code());
    }

    /** A verbose provider must not be able to flood the log through the diagnostic. */
    @Test
    void oversizedProviderBody_isCapped() {
        String body = "x".repeat(5_000);
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> ProviderCall.await(() -> { throw httpError(400, body); },
                        ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google tokeninfo"));

        assertTrue(ex.getStatusCode().detail().length() < 400,
                "diagnostic should be capped, was " + ex.getStatusCode().detail().length());
    }
}
