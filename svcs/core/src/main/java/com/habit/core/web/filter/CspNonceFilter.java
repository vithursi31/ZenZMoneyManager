package com.habit.core.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CspNonceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/") || path.startsWith("/static/") || path.startsWith("/stripe/")) {
            chain.doFilter(request, response);
            return;
        }

        byte[] nonceBytes = new byte[16];
        try {
            SecureRandom.getInstanceStrong().nextBytes(nonceBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        request.setAttribute("cspNonce", nonce);

        response.setHeader("Content-Security-Policy",
                "script-src 'nonce-" + nonce + "' 'strict-dynamic'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: https:; " +
                "font-src 'self'; " +
                "object-src 'none'; " +
                "base-uri 'self';");

        chain.doFilter(request, response);
    }
}
