package com.zenzmoney.core.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * Provider calls are logged here, at the one public entry point, rather than around each
     * outbound request: a failure surfaces as UnauthorizedException and is translated once in
     * GlobalExceptionHandler, but that line cannot say WHICH provider call broke or how slow it was.
     * A provider outage and a misconfigured client id look identical to the caller; they do not look
     * identical here. Never log the identity token or authorization code — both are live credentials.
     */
    private static final Logger log = LoggerFactory.getLogger(AppleAuthConnector.class);


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
            log.error("Apple sign-in is not configured — EC private key is missing");
            throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                    .with("Apple sign-in is not available right now."));
        }
        String clientId = req.isMobileApp() ? clientIdMobile : clientIdWeb;
        if (clientId == null || clientId.isBlank()) {
            log.error("Apple sign-in is not configured — client id is missing (mobile={})",
                    req.isMobileApp());
            throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                    .with("Apple sign-in is not available right now."));
        }
        long startedAt = System.currentTimeMillis();
        try {
            validateIdToken(req.getIdentityToken(), clientId);
            AppleAuthResp resp = exchangeAuthorizationCode(req.getAuthorizationCode(), clientId);
            log.debug("Apple verification succeeded in {}ms (mobile={})",
                    System.currentTimeMillis() - startedAt, req.isMobileApp());
            return resp;
        } catch (RuntimeException e) {
            log.warn("Apple verification failed in {}ms (mobile={}): {}",
                    System.currentTimeMillis() - startedAt, req.isMobileApp(), e.getMessage());
            throw e;
        }
    }

    private void validateIdToken(String idToken, String clientId) {
        Claims claims = decodeIdToken(idToken);
        if (!APPLE_ISSUER.equals(claims.getIssuer())) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Invalid Apple token issuer"));
        }
        Set<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(clientId)) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Apple token issued for a different app"));
        }
        Date exp = claims.getExpiration();
        if (exp == null || exp.before(new Date())) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Apple id token expired"));
        }
    }

    @SuppressWarnings("unchecked")
    private Claims decodeIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new UnauthorizedException(
                        ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Malformed Apple id token"));
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            Map<String, Object> header = mapper.readValue(headerJson, Map.class);
            String kid = (String) header.get("kid");
            RSAPublicKey pub = getApplePublicKey(kid);
            return Jwts.parser().verifyWith(pub).build().parseSignedClaims(idToken).getPayload();
        } catch (ServiceException e) {
            // Fetching Apple's key set can fail with a 502 code of its own. Without this, the catch
            // below would relabel a provider outage as a bad token and blame the caller for it.
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Apple id token verification failed: " + e.getMessage()));
        } catch (Exception e) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Apple id token parse failed: " + e.getMessage()));
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
            throw new ServiceException(
                    ServiceCodes.SC_APPLE_CONNECTOR_ERROR.with("Apple keys endpoint returned empty"));
        }
        List<Map<String, String>> keys = (List<Map<String, String>>) json.get("keys");
        if (keys == null) {
            throw new ServiceException(ServiceCodes.SC_APPLE_CONNECTOR_ERROR.with("Apple keys missing"));
        }
        for (Map<String, String> k : keys) {
            if (kid.equals(k.get("kid"))) {
                try {
                    byte[] n = Base64.getUrlDecoder().decode(k.get("n"));
                    byte[] e = Base64.getUrlDecoder().decode(k.get("e"));
                    return (RSAPublicKey) KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
                } catch (Exception ex) {
                    throw new ServiceException(ServiceCodes.SC_APPLE_CONNECTOR_ERROR.with("Apple key parse failed"));
                }
            }
        }
        throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                .with("Apple key not found for kid: " + kid));
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
            throw new ServiceException(ServiceCodes.SC_APPLE_CONNECTOR_ERROR.with(
                    "Apple token exchange failed: " + (body == null ? "no response" : body.get("error"))));
        }

        String idToken = (String) body.get("id_token");
        if (idToken == null) {
            throw new ServiceException(
                    ServiceCodes.SC_APPLE_CONNECTOR_ERROR.with("Apple did not return id_token"));
        }
        try {
            String[] parts = idToken.split("\\.");
            Map<String, Object> payload = mapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), Map.class);
            AppleAuthResp r = new AppleAuthResp();
            r.setEmail((String) payload.get("email"));
            return r;
        } catch (Exception e) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Failed to parse Apple id_token: " + e.getMessage()));
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
