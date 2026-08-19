package com.zenzmoney.core.service;

import com.zenzmoney.common.domain.UserStatus;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtTokenService {

    public static final String TYPE_ACCESS  = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "type";

    private final SecretKey signingKey;
    private final long accessTokenTtlMs;
    private final long refreshTokenTtlMs;
    private final UserRepository userRepository;

    public JwtTokenService(
            @Value("${zenzmoney.jwt.secret:default-secret-key-change-in-production-must-be-at-least-256-bits-long}") String secret,
            @Value("${zenzmoney.jwt.access-token-expiration:3600000}") long accessTokenTtlMs,
            @Value("${zenzmoney.jwt.refresh-token-expiration:2592000000}") long refreshTokenTtlMs,
            UserRepository userRepository) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMs = accessTokenTtlMs;
        this.refreshTokenTtlMs = refreshTokenTtlMs;
        this.userRepository = userRepository;
    }

    public String generateAccessToken(String email) {
        return build(email, TYPE_ACCESS, accessTokenTtlMs);
    }

    public String generateRefreshToken(String email) {
        return build(email, TYPE_REFRESH, refreshTokenTtlMs);
    }

    private String build(String subject, String type, long ttlMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(subject)
                .claim(CLAIM_TYPE, type)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMs))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses + verifies signature/expiry. For access and refresh tokens, also
     * rejects if the user no longer exists or the account isn't active.
     */
    public Claims extractClaims(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("INVALID_TOKEN", e.getMessage());
        }

        String type = claims.get(CLAIM_TYPE, String.class);
        if (type == null) {
            throw new UnauthorizedException("INVALID_TOKEN", "Missing token type");
        }

        if (TYPE_ACCESS.equals(type) || TYPE_REFRESH.equals(type)) {
            String email = claims.getSubject();
            if (email == null || email.isBlank()) {
                throw new UnauthorizedException("INVALID_TOKEN", "Missing subject");
            }
            Optional<User> u = userRepository.findByEmail(email);
            if (u.isEmpty()) {
                throw new UnauthorizedException("INVALID_TOKEN", "Account does not exist");
            }
            if (u.get().getStatus() != UserStatus.ACTIVE) {
                throw new UnauthorizedException("INVALID_TOKEN", "Account is not active");
            }
        }

        return claims;
    }

    public String extractTokenType(Claims claims) {
        return claims.get(CLAIM_TYPE, String.class);
    }

    public String extractSubject(Claims claims) {
        return claims.getSubject();
    }
}
