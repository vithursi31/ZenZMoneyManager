package com.habit.core.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit.common.dto.ApiResponse;
import com.habit.common.exception.UnauthorizedException;
import com.habit.core.service.JwtTokenService;
import io.jsonwebtoken.Claims;
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
                writeError(response, "INVALID_TOKEN",
                        "Required access token but found '" + type + "'");
                return;
            }

            String email = claims.getSubject();
            UserDetails details = userDetailsService.loadUserByUsername(email);

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            chain.doFilter(request, response);
        } catch (UnauthorizedException e) {
            writeError(response, e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            writeError(response, "INVALID_TOKEN", e.getMessage());
        }
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

    private void writeError(HttpServletResponse resp, String code, String desc) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        objectMapper.writeValue(resp.getOutputStream(), ApiResponse.error(code, desc));
    }
}
