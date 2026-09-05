package com.zenzmoney.core.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
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
     * identical here. Never log the identity token — it is a live credential.
     */
    private static final Logger log = LoggerFactory.getLogger(AppleAuthConnector.class);


    private static final String APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISSUER   = "https://appleid.apple.com";

    private static final long KEY_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String clientIdMobile;
    private final String clientIdWeb;

    private final Map<String, CachedKey> keyCache = new ConcurrentHashMap<>();

    private record CachedKey(RSAPublicKey key, long expiresAt) {}

    public AppleAuthConnector(@Qualifier("oauthWebClient") WebClient webClient,
                              @Value("${zenzmoney.apple.client-id:}") String clientIdMobile,
                              @Value("${zenzmoney.apple.client-id-web:}") String clientIdWeb) {
        this.webClient = webClient;
        this.clientIdMobile = clientIdMobile;
        this.clientIdWeb = clientIdWeb;
    }

    /**
     * Verifies the identity token the client received from Apple, and reads the account
     * identity out of it.
     *
     * <p>There is deliberately no authorization-code exchange. This used to verify the
     * identity token, discard its claims, and call Apple's token endpoint for a <i>second</i>
     * id_token purely to read the email off it — a round trip that bought nothing (the token
     * already in hand carries the same claims, signature-checked), broke any client retry
     * (an authorization code is single-use), and ended in a payload parsed without verifying
     * its signature. Apple's refresh token was the only thing the exchange could have earned
     * and nothing here uses it, so the exchange, the ES256 client-secret JWT, and the EC
     * private key it needed are all gone — which also removes a malformed key's ability to
     * fail app startup.
     */
    public AppleAuthResp verifyAuth(AppleAuthRequest req) {
        String clientId = req.isMobileApp() ? clientIdMobile : clientIdWeb;
        if (clientId == null || clientId.isBlank()) {
            log.error("Apple sign-in is not configured — client id is missing (mobile={})",
                    req.isMobileApp());
            throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                    .with(Msg.OAUTH_UNAVAILABLE, "Apple"));
        }
        if (req.getIdentityToken() == null || req.getIdentityToken().isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_REQUEST_INVALID
                    .with("Missing Apple identity token"));
        }

        long startedAt = System.currentTimeMillis();
        try {
            AppleAuthResp resp = readIdentity(req.getIdentityToken(), clientId, req.getNonce());
            log.debug("Apple verification succeeded in {}ms (mobile={})",
                    System.currentTimeMillis() - startedAt, req.isMobileApp());
            return resp;
        } catch (RuntimeException e) {
            log.warn("Apple verification failed in {}ms (mobile={}): {}",
                    System.currentTimeMillis() - startedAt, req.isMobileApp(), e.getMessage());
            throw e;
        }
    }

    private AppleAuthResp readIdentity(String idToken, String clientId, String requestNonce) {
        Claims claims = decodeIdToken(idToken);

        if (!APPLE_ISSUER.equals(claims.getIssuer())) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Invalid Apple token issuer"));
        }
        Set<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(clientId)) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Apple token issued for a different app"));
        }
        // exp is enforced by the parser in decodeIdToken — ExpiredJwtException is a JwtException
        // and comes back as E1071 — so there is no manual clock comparison here.
        Date exp = claims.getExpiration();
        if (exp == null) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Apple token has no expiry"));
        }
        requireMatchingNonce(claims, requestNonce);

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Apple token has no subject"));
        }

        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_EMAIL_UNVERIFIED);
        }
        // Explicitly false is a refusal; absent is not. Unlike Google — where an unverified
        // address is a real state a token can carry — every address Apple puts in a signed
        // token is one it controls: the Apple ID's own verified email, or a relay Apple owns.
        Object verified = claims.get("email_verified");
        if (verified != null && !Boolean.parseBoolean(String.valueOf(verified))) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_EMAIL_UNVERIFIED);
        }

        AppleAuthResp r = new AppleAuthResp();
        r.setSubject(subject);
        r.setEmail(email);
        return r;
    }

    /**
     * Binds the token to the sign-in attempt when the client supplied a nonce.
     *
     * <p>Accepts the raw value or its SHA-256, because the two Sign in with Apple client
     * conventions differ on which one reaches Apple: some SDKs hand Apple a hash and keep the
     * raw value, so requiring one spelling would reject every login from the other.
     *
     * <p>A client-generated nonce only proves the token was minted for <i>a</i> nonce the caller
     * knows, so this is not replay protection on its own — that needs a nonce this server issues
     * and remembers. It is worth having anyway: it stops a token minted for one sign-in attempt
     * being presented against another, and it stops the field being decoration. Absent nonce is
     * allowed: Apple omits the claim when the client never sent one.
     */
    private void requireMatchingNonce(Claims claims, String requestNonce) {
        if (requestNonce == null || requestNonce.isBlank()) {
            return;
        }
        String tokenNonce = claims.get("nonce", String.class);
        if (tokenNonce == null || tokenNonce.isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Apple token carries no nonce but one was supplied"));
        }
        if (!tokenNonce.equals(requestNonce) && !tokenNonce.equals(sha256Hex(requestNonce))) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Apple token nonce mismatch"));
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
            if (kid == null || kid.isBlank()) {
                throw new UnauthorizedException(
                        ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Apple id token has no kid"));
            }
            RSAPublicKey pub = getApplePublicKey(kid);
            return Jwts.parser().verifyWith(pub).build().parseSignedClaims(idToken).getPayload();
        } catch (ServiceException e) {
            // Covers UnauthorizedException, which extends it. Fetching Apple's key set can fail
            // with a 502 code of its own, and the checks above raise their own 401s. Without this,
            // the catch below would relabel a provider outage as a bad token and blame the caller.
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
        Map<String, Object> json = ProviderCall.await(() -> webClient.get()
                .uri(APPLE_KEYS_URL)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_APPLE_CONNECTOR_ERROR, "Apple keys");
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
}
