package com.zenzmoney.core.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppleAuthConnector {

    private static final String APPLE_AUTH_URL = "https://appleid.apple.com/auth/token";
    private static final String APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISSUER   = "https://appleid.apple.com";

    private static final long KEY_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @Value("${zenzmoney.apple.client-id:}")     private String clientIdMobile;
    @Value("${zenzmoney.apple.client-id-web:}") private String clientIdWeb;
    @Value("${zenzmoney.apple.team-id:}")       private String teamId;
    @Value("${zenzmoney.apple.key-id:}")        private String keyId;
    @Value("${zenzmoney.apple.private-key:}")   private String privateKeyB64;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient = WebClient.builder().build();

    private ECPrivateKey privateKey;
    private final Map<String, CachedKey> keyCache = new ConcurrentHashMap<>();

    private record CachedKey(RSAPublicKey key, long expiresAt) {}

    @PostConstruct
    void init() {
        if (privateKeyB64 != null && !privateKeyB64.isBlank()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(privateKeyB64);
                this.privateKey = (ECPrivateKey) KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load Apple EC private key", e);
            }
        }
    }

    public AppleAuthResp verifyAuth(AppleAuthRequest req) {
        if (privateKey == null) {
            throw new UnauthorizedException("CONFIG_MISSING", "Apple OAuth is not configured");
        }
        String clientId = req.isMobileApp() ? clientIdMobile : clientIdWeb;
        if (clientId == null || clientId.isBlank()) {
            throw new UnauthorizedException("CONFIG_MISSING", "Apple client id is not configured");
        }
        validateIdToken(req.getIdentityToken(), clientId);
        return exchangeAuthorizationCode(req.getAuthorizationCode(), clientId);
    }

    private void validateIdToken(String idToken, String clientId) {
        Claims claims = decodeIdToken(idToken);
        if (!APPLE_ISSUER.equals(claims.getIssuer())) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Invalid issuer");
        }
        Set<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(clientId)) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Invalid audience");
        }
        Date exp = claims.getExpiration();
        if (exp == null || exp.before(new Date())) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Apple id token expired");
        }
    }

    @SuppressWarnings("unchecked")
    private Claims decodeIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new UnauthorizedException("VALIDATION_FAILED", "Malformed Apple id token");
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            Map<String, Object> header = mapper.readValue(headerJson, Map.class);
            String kid = (String) header.get("kid");
            RSAPublicKey pub = getApplePublicKey(kid);
            return Jwts.parser().verifyWith(pub).build().parseSignedClaims(idToken).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Apple id token verification failed: " + e.getMessage());
        } catch (Exception e) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Apple id token parse failed: " + e.getMessage());
        }
    }

    private RSAPublicKey getApplePublicKey(String kid) {
        long now = System.currentTimeMillis();
        CachedKey cached = keyCache.get(kid);
        if (cached != null && cached.expiresAt > now) {
            return cached.key;
        }
        RSAPublicKey fresh = fetchApplePublicKey(kid);
        keyCache.put(kid, new CachedKey(fresh, now + KEY_CACHE_TTL_MS));
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private RSAPublicKey fetchApplePublicKey(String kid) {
        Map<String, Object> json = webClient.get()
                .uri(APPLE_KEYS_URL)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();
        if (json == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Apple keys endpoint returned empty");
        }
        List<Map<String, String>> keys = (List<Map<String, String>>) json.get("keys");
        if (keys == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Apple keys missing");
        }
        for (Map<String, String> k : keys) {
            if (kid.equals(k.get("kid"))) {
                try {
                    byte[] n = Base64.getUrlDecoder().decode(k.get("n"));
                    byte[] e = Base64.getUrlDecoder().decode(k.get("e"));
                    return (RSAPublicKey) KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
                } catch (Exception ex) {
                    throw new UnauthorizedException("VALIDATION_FAILED", "Apple key parse failed");
                }
            }
        }
        throw new UnauthorizedException("VALIDATION_FAILED", "Apple key not found for kid: " + kid);
    }

    @SuppressWarnings("unchecked")
    private AppleAuthResp exchangeAuthorizationCode(String code, String clientId) {
        String jwt = buildClientSecretJwt(clientId);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", jwt);
        form.add("code", code);
        form.add("grant_type", "authorization_code");

        Map<String, Object> body = webClient.post()
                .uri(APPLE_AUTH_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();

        if (body == null || body.containsKey("error")) {
            throw new UnauthorizedException("VALIDATION_FAILED",
                    "Apple token exchange failed: " + (body == null ? "no response" : body.get("error")));
        }

        String idToken = (String) body.get("id_token");
        if (idToken == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Apple did not return id_token");
        }
        try {
            String[] parts = idToken.split("\\.");
            Map<String, Object> payload = mapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), Map.class);
            AppleAuthResp r = new AppleAuthResp();
            r.setEmail((String) payload.get("email"));
            return r;
        } catch (Exception e) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Failed to parse Apple id_token: " + e.getMessage());
        }
    }

    private String buildClientSecretJwt(String clientId) {
        long now = System.currentTimeMillis();
        Map<String, Object> header = new HashMap<>();
        header.put("kid", keyId);
        return Jwts.builder()
                .header().add(header).and()
                .issuer(teamId)
                .subject(clientId)
                .audience().add(APPLE_ISSUER).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + 86_400_000L))
                .signWith(privateKey, SignatureAlgorithm.ES256)
                .compact();
    }
}
