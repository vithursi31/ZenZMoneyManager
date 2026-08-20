package com.zenzmoney.core.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.dto.ApiResponse;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.service.JwtTokenService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Token rejections are audited, not just logged: a run of them is either a client bug or someone
     * probing with forged tokens, and telling those apart after the fact needs the history. The token
     * itself is never logged — it is a live credential until it expires.
     */
    private static final Logger audit = AppLog.AUDIT;

    private final JwtTokenService jwtTokenService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/authenticate",
            "/api/v1/register",
            "/api/v1/refresh-token",
            "/api/v1/forgot-password",
            "/api/v1/reset-password",
            "/api/v1/verify-email",
            "/api/v1/service-status"
    );

    private static final List<SimpleGrantedAuthority> ANONYMOUS_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"));

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   UserDetailsService userDetailsService) {
        this.jwtTokenService = jwtTokenService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);

        if (token == null) {
            setAnonymous();
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtTokenService.extractClaims(token);
            String type = jwtTokenService.extractTokenType(claims);
            if (!JwtTokenService.TYPE_ACCESS.equals(type)) {
                // A refresh token presented as an access token. Deliberate misuse or a client bug —
                // either way it is the one token-type confusion this filter exists to stop.
                audit.warn("Token rejected on {}: required access token, got '{}' (subject={})",
                        path, type, claims.getSubject());
                writeError(response, ServiceCodes.SC_TOKEN_TYPE_MISMATCH
                        .with("Required access token but found '" + type + "'"));
                return;
            }

            String email = claims.getSubject();
            UserDetails details = userDetailsService.loadUserByUsername(email);

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("Authenticated {} for {}", email, path);
        } catch (UnauthorizedException e) {
            audit.warn("Token rejected on {}: {} — {}", path,
                    e.getStatusCode().code(), e.getMessage());
            writeError(response, e.getStatusCode());
            return;
        } catch (Exception e) {
            // Anything JwtTokenService did not already label (it answers expiry and malformed tokens
            // with their own codes). e.getMessage() carries no secret, so it is safe to record.
            audit.warn("Token rejected on {}: {}", path, e.getMessage());
            writeError(response, ServiceCodes.SC_TOKEN_INVALID.with(e.getMessage()));
            return;
        }

        // Deliberately outside the try: only token validation is being guarded above. With the
        // chain inside it, any failure from the controller or service downstream was caught here
        // and reported as INVALID_TOKEN — sending clients to refresh a token that was never the
        // problem, and filing a server bug under "Token rejected" in audit.log.
        chain.doFilter(request, response);
    }

    private void setAnonymous() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "anonymousKey", "anonymous", ANONYMOUS_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(anon);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        String q = request.getParameter("authorization");
        return (q != null && !q.isEmpty()) ? q : null;
    }

    private void writeError(HttpServletResponse resp, StatusCode statusCode) throws IOException {
        resp.setStatus(statusCode.httpStatus());
        resp.setContentType("application/json");
        objectMapper.writeValue(resp.getOutputStream(), ApiResponse.error(statusCode));
    }
}
