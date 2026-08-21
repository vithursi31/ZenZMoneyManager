package com.zenzmoney.core.web.filter;

import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.i18n.TestMessages;
import com.zenzmoney.core.service.JwtTokenService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The filter guards <em>token validation</em> and nothing else.
 *
 * <p>It used to run the rest of the filter chain inside that try block, so any failure from a
 * controller or service downstream was caught and answered {@code 401 INVALID_TOKEN} — telling a
 * client to refresh a perfectly good token, and filing the server's own bug under "Token rejected"
 * in audit.log. These tests pin the boundary.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtTokenService jwtTokenService;
    @Mock UserDetailsService userDetailsService;
    @Mock Claims claims;

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(jwtTokenService, userDetailsService, TestMessages.resolver());
    }

    private MockHttpServletRequest authenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions");
        request.addHeader("Authorization", "Bearer good-token");
        return request;
    }

    private void stubValidAccessToken() {
        when(jwtTokenService.extractClaims("good-token")).thenReturn(claims);
        when(jwtTokenService.extractTokenType(claims)).thenReturn(JwtTokenService.TYPE_ACCESS);
        when(claims.getSubject()).thenReturn("jane@example.com");
        when(userDetailsService.loadUserByUsername("jane@example.com"))
                .thenReturn(User.withUsername("jane@example.com").password("x").authorities(List.of()).build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The regression. A downstream failure must propagate as itself so the container answers 500 —
     * not be converted into an auth error.
     */
    @Test
    void downstreamFailureIsNotReportedAsAnAuthError() throws Exception {
        stubValidAccessToken();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            throw new ServletException("Handler dispatch failed: java.lang.NoSuchMethodError");
        };

        ServletException thrown = assertThrows(ServletException.class,
                () -> filter().doFilter(authenticated(), response, exploding));

        assertTrue(thrown.getMessage().contains("NoSuchMethodError"));
        assertEquals(200, response.getStatus());                 // the filter wrote no error of its own
        assertTrue(response.getContentAsString().isEmpty());
    }

    /** A valid token still authenticates and continues the chain. */
    @Test
    void validAccessToken_authenticatesAndContinues() throws Exception {
        stubValidAccessToken();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter().doFilter(authenticated(), response, (req, res) -> reached[0] = true);

        assertTrue(reached[0]);
        assertEquals("jane@example.com",
                SecurityContextHolder.getContext().getAuthentication().getName());
    }

    /** A refresh token presented as an access token is still refused, and the chain never runs. */
    @Test
    void refreshTokenAsAccessToken_isRefused_andChainNotRun() throws Exception {
        when(jwtTokenService.extractClaims("good-token")).thenReturn(claims);
        when(jwtTokenService.extractTokenType(claims)).thenReturn("refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter().doFilter(authenticated(), response, (req, res) -> reached[0] = true);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains(ServiceCodes.SC_TOKEN_TYPE_MISMATCH.code()));
        assertTrue(!reached[0], "the chain must not run once the token is refused");
    }

    /** A malformed token is still the filter's own business, and still answers an invalid-token code. */
    @Test
    void malformedToken_stillAnswersInvalidToken() throws Exception {
        when(jwtTokenService.extractClaims("good-token"))
                .thenThrow(new IllegalArgumentException("JWT strings must contain exactly 2 period characters"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter().doFilter(authenticated(), response, (req, res) -> reached[0] = true);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains(ServiceCodes.SC_TOKEN_INVALID.code()));
        assertTrue(!reached[0]);
    }

    @Test
    void unauthorizedException_keepsItsOwnErrorCode() throws Exception {
        when(jwtTokenService.extractClaims("good-token"))
                .thenThrow(new UnauthorizedException(ServiceCodes.SC_TOKEN_EXPIRED));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(authenticated(), response, (req, res) -> { });

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains(ServiceCodes.SC_TOKEN_EXPIRED.code()));
    }

    /** No token is anonymous, not an error — the 401/403 comes later from method security. */
    @Test
    void noToken_proceedsAsAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter().doFilter(request, response, (req, res) -> reached[0] = true);

        assertTrue(reached[0]);
        assertEquals(200, response.getStatus());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ANONYMOUS")));
    }
}
