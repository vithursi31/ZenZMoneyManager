package com.zenzmoney.core.web.filter;

import com.zenzmoney.core.web.util.AuthUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates the {@code cid} (correlation id) and {@code user} MDC keys that every appender pattern
 * in {@code logback-spring.xml} prints, so all lines emitted while serving one request can be
 * grepped together — including the lines written from a different file or a different channel.
 *
 * <p>Deliberately a filter rather than something each controller calls: the mindmapai services set
 * these keys in service/controller code and pair every entry point with its own {@code MDC.clear()},
 * which means a new entry point that forgets the clear leaks the previous request's identity onto
 * the next request that reuses the worker thread. Doing it once at the edge makes that impossible.
 *
 * <p>Registered in {@code SecurityConfig} <em>after</em> {@link JwtAuthenticationFilter} so the
 * principal is already resolved and {@code user} is the real caller rather than {@code anonymous}.
 * It is intentionally not a {@code @Component}: Spring Boot auto-registers any {@code Filter} bean
 * into the servlet chain, which would run an instance ahead of the security chain and — because
 * {@link OncePerRequestFilter} suppresses the second pass — pin {@code user} to {@code anonymous}.
 */
public class MdcContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MdcContextFilter.class);

    static final String CID_KEY = "cid";
    static final String USER_KEY = "user";
    static final String CID_HEADER = "X-Correlation-Id";

    /** Cap on an inbound correlation id; long enough for a UUID or a trace id, short enough to log. */
    private static final int MAX_CID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String cid = resolveCorrelationId(request);
        MDC.put(CID_KEY, cid);
        MDC.put(USER_KEY, AuthUtil.currentUsername());
        response.setHeader(CID_HEADER, cid);

        long startedAt = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            // DEBUG so this lands only in debug.log, not in info.log.
            // The request URI is logged WITHOUT its query string on purpose: this app accepts
            // ?authorization=<token> as an alternative to the bearer header, so a logged query
            // string would write a live access token into a file with 14-day retention.
            log.debug("{} {} -> {} in {}ms",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    System.currentTimeMillis() - startedAt);
            // Worker threads are pooled and reused: without this the next request served by this
            // thread inherits the previous caller's id until it overwrites the keys.
            MDC.remove(CID_KEY);
            MDC.remove(USER_KEY);
        }
    }

    /**
     * Honours a caller-supplied {@code X-Correlation-Id} so a client (or a future reverse proxy) can
     * stitch its own logs to the server's, falling back to a fresh id.
     *
     * <p>The header is attacker-controlled and ends up in a log file, so it is sanitised rather than
     * trusted: anything outside {@code [A-Za-z0-9_-]} would let a caller embed a newline and forge
     * whole log lines, which is how a reader gets misled about what actually happened.
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(CID_HEADER);
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = supplied.strip();
        if (trimmed.length() > MAX_CID_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CID_LENGTH);
        }
        String sanitised = trimmed.replaceAll("[^A-Za-z0-9_-]", "");
        return sanitised.isEmpty() ? UUID.randomUUID().toString() : sanitised;
    }
}
