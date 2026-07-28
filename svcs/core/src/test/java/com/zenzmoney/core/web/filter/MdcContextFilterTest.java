package com.zenzmoney.core.web.filter;

import com.zenzmoney.core.web.util.AuthUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcContextFilterTest {

    private final MdcContextFilter filter = new MdcContextFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void populatesCidAndUserForTheDurationOfTheRequest() throws Exception {
        authenticateAs("someone@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = new String[2];
        filter.doFilter(request, response, (req, res) -> {
            seen[0] = MDC.get(MdcContextFilter.CID_KEY);
            seen[1] = MDC.get(MdcContextFilter.USER_KEY);
        });

        assertNotNull(seen[0], "cid must be set while the chain runs");
        assertEquals("someone@example.com", seen[1]);
    }

    @Test
    void recordsAnonymousWhenThereIsNoAuthenticatedCaller() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] user = new String[1];
        filter.doFilter(request, response,
                (req, res) -> user[0] = MDC.get(MdcContextFilter.USER_KEY));

        assertEquals(AuthUtil.ANONYMOUS, user[0]);
    }

    /**
     * The leak this filter exists to prevent: worker threads are pooled, so anything left in MDC
     * after a response is written is attributed to whichever request the thread serves next.
     */
    @Test
    void clearsMdcAfterTheRequestSoThePooledThreadCarriesNothingForward() throws Exception {
        authenticateAs("first@example.com");

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"),
                new MockHttpServletResponse(), (req, res) -> {});

        assertNull(MDC.get(MdcContextFilter.CID_KEY));
        assertNull(MDC.get(MdcContextFilter.USER_KEY));
    }

    @Test
    void clearsMdcEvenWhenTheChainThrows() {
        authenticateAs("first@example.com");

        assertThrows(IllegalStateException.class, () ->
                filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"),
                        new MockHttpServletResponse(),
                        (req, res) -> { throw new IllegalStateException("handler blew up"); }));

        assertNull(MDC.get(MdcContextFilter.CID_KEY));
        assertNull(MDC.get(MdcContextFilter.USER_KEY));
    }

    @Test
    void reusesACallerSuppliedCorrelationIdAndEchoesItBack() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(MdcContextFilter.CID_HEADER, "client-trace-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] cid = new String[1];
        filter.doFilter(request, response,
                (req, res) -> cid[0] = MDC.get(MdcContextFilter.CID_KEY));

        assertEquals("client-trace-42", cid[0]);
        assertEquals("client-trace-42", response.getHeader(MdcContextFilter.CID_HEADER));
    }

    @Test
    void generatesACorrelationIdWhenTheHeaderIsAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] cid = new String[1];
        filter.doFilter(new MockHttpServletRequest("GET", "/"), response,
                (req, res) -> cid[0] = MDC.get(MdcContextFilter.CID_KEY));

        assertNotNull(cid[0]);
        assertDoesNotThrow(() -> UUID.fromString(cid[0]), "fallback should be a UUID");
        assertEquals(cid[0], response.getHeader(MdcContextFilter.CID_HEADER));
    }

    /**
     * The header is caller-controlled and lands in a log file, so a newline in it would let the
     * caller forge whole log lines and mislead whoever reads them afterwards.
     */
    @Test
    void stripsCarriageReturnsAndNewlinesFromASuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(MdcContextFilter.CID_HEADER,
                "abc\r\n2026-07-29 10:00:00.000 [main] ERROR forged - transfer approved");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] cid = new String[1];
        filter.doFilter(request, response,
                (req, res) -> cid[0] = MDC.get(MdcContextFilter.CID_KEY));

        assertTrue(cid[0].matches("[A-Za-z0-9_-]+"),
                "cid must be reduced to safe characters, was: " + cid[0]);
    }

    @Test
    void truncatesAnOverlongCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(MdcContextFilter.CID_HEADER, "z".repeat(500));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] cid = new String[1];
        filter.doFilter(request, response,
                (req, res) -> cid[0] = MDC.get(MdcContextFilter.CID_KEY));

        assertEquals(64, cid[0].length());
    }

    @Test
    void fallsBackToAGeneratedIdWhenTheSuppliedOneSanitisesToNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(MdcContextFilter.CID_HEADER, "!!!///###");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] cid = new String[1];
        filter.doFilter(request, response,
                (req, res) -> cid[0] = MDC.get(MdcContextFilter.CID_KEY));

        assertDoesNotThrow(() -> UUID.fromString(cid[0]));
    }
}
