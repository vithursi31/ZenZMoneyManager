package com.zenzmoney.core.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.core.web.dto.AppleAuthRequest;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verification is done locally against Apple's published key set, so this signs real tokens
 * with a throwaway RSA key and serves the matching JWKS — a token that only <em>looks</em>
 * right has to fail here, not be trusted because it parsed.
 *
 * <p>There is no authorization-code exchange to test: the connector reads the identity out of
 * the token the client already holds. It used to verify that token, discard its claims, and
 * fetch a second one from Apple purely to read the email off a payload it never verified.
 */
class AppleAuthConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String MOBILE_ID    = "com.zenzmoney.app";
    private static final String WEB_ID       = "com.zenzmoney.web";
    private static final String KID          = "test-kid";
    private static final String SUBJECT      = "001234.fedcba9876543210.1234";

    private static final KeyPair APPLE_KEY = rsaKeyPair();
    private static final KeyPair OTHER_KEY = rsaKeyPair();

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Apple's /auth/keys shape, built from the throwaway key so signatures actually verify. */
    private static Map<String, Object> jwks(String kid, RSAPublicKey pub) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return Map.of("keys", List.of(Map.of(
                "kty", "RSA",
                "kid", kid,
                "use", "sig",
                "alg", "RS256",
                "n", b64.encodeToString(pub.getModulus().toByteArray()),
                "e", b64.encodeToString(pub.getPublicExponent().toByteArray()))));
    }

    private AppleAuthConnector connector(Supplier<ClientResponse> keysResponse) {
        ExchangeFunction exchange = request -> Mono.just(keysResponse.get());
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        return new AppleAuthConnector(webClient, MOBILE_ID, WEB_ID);
    }

    private AppleAuthConnector connector() {
        return connector(() -> json(HttpStatus.OK, jwks(KID, (RSAPublicKey) APPLE_KEY.getPublic())));
    }

    private static ClientResponse json(HttpStatus status, Map<String, Object> body) {
        try {
            return ClientResponse.create(status)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(MAPPER.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A well-formed Apple identity token, with whatever the test wants to bend. */
    private static String token(Map<String, Object> overrides, KeyPair signer, String kid) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", APPLE_ISSUER);
        claims.put("aud", MOBILE_ID);
        claims.put("sub", SUBJECT);
        claims.put("email", "abc123@privaterelay.appleid.com");
        claims.put("email_verified", "true");
        claims.putAll(overrides);

        long now = System.currentTimeMillis();
        Object exp = claims.remove("__exp");
        Date expiry = exp instanceof Date d ? d : new Date(now + 600_000);

        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .issuedAt(new Date(now - 1000))
                .expiration(expiry)
                .signWith(signer.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String token(Map<String, Object> overrides) {
        return token(overrides, APPLE_KEY, KID);
    }

    private static AppleAuthRequest mobileRequest(String identityToken) {
        AppleAuthRequest r = new AppleAuthRequest();
        r.setIdentityToken(identityToken);
        r.setMobileApp(true);
        return r;
    }

    private ServiceException failureFor(AppleAuthRequest req) {
        return assertThrows(ServiceException.class, () -> connector().verifyAuth(req));
    }

    @Test
    void validToken_returnsTheSubjectAndEmail() {
        AppleAuthResp resp = connector().verifyAuth(mobileRequest(token(Map.of())));

        assertEquals(SUBJECT, resp.getSubject(), "the stable identity behind a rotating relay address");
        assertEquals("abc123@privaterelay.appleid.com", resp.getEmail());
    }

    @Test
    void webClientId_isUsedWhenTheRequestIsNotMobile() {
        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken(token(Map.of("aud", WEB_ID)));
        req.setMobileApp(false);

        assertNotNull(connector().verifyAuth(req).getEmail());
    }

    @Test
    void tokenForAnotherApp_isRejected() {
        assertEquals("E1071",
                failureFor(mobileRequest(token(Map.of("aud", "com.someone.else")))).getStatusCode().code());
    }

    /** A mobile token presented on the web path is a token for a different audience. */
    @Test
    void mobileTokenOnTheWebPath_isRejected() {
        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken(token(Map.of()));
        req.setMobileApp(false);
        assertEquals("E1071", failureFor(req).getStatusCode().code());
    }

    @Test
    void wrongIssuer_isRejected() {
        assertEquals("E1071",
                failureFor(mobileRequest(token(Map.of("iss", "https://evil.example")))).getStatusCode().code());
    }

    @Test
    void expiredToken_isRejected() {
        Map<String, Object> expired = new HashMap<>();
        expired.put("__exp", new Date(System.currentTimeMillis() - 60_000));
        assertEquals("E1071", failureFor(mobileRequest(token(expired))).getStatusCode().code());
    }

    /** The whole point of verifying locally: a token Apple did not sign must not pass. */
    @Test
    void tokenSignedByAnotherKey_isRejected() {
        String forged = token(Map.of(), OTHER_KEY, KID);
        assertEquals("E1071", failureFor(mobileRequest(forged)).getStatusCode().code());
    }

    @Test
    void tokenWithAnUnknownKid_isRejected() {
        String unknownKid = token(Map.of(), APPLE_KEY, "some-other-kid");
        assertEquals("E1071", failureFor(mobileRequest(unknownKid)).getStatusCode().code());
    }

    @Test
    void malformedToken_isRejected() {
        assertEquals("E1071", failureFor(mobileRequest("not-a-jwt")).getStatusCode().code());
    }

    @Test
    void explicitlyUnverifiedEmail_isRejected() {
        assertEquals("E1072",
                failureFor(mobileRequest(token(Map.of("email_verified", "false")))).getStatusCode().code());
    }

    @Test
    void tokenWithNoEmail_isRejected() {
        Map<String, Object> noEmail = new HashMap<>();
        noEmail.put("email", null);
        assertEquals("E1072", failureFor(mobileRequest(token(noEmail))).getStatusCode().code());
    }

    /**
     * Absent {@code email_verified} is allowed here, unlike Google: every address Apple puts in
     * a signed token is one it controls — the Apple ID's verified email, or a relay Apple owns.
     */
    @Test
    void absentEmailVerified_isAcceptedForApple() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email_verified", null);
        assertEquals("abc123@privaterelay.appleid.com",
                connector().verifyAuth(mobileRequest(token(claims))).getEmail());
    }

    @Test
    void tokenWithNoSubject_isRejected() {
        Map<String, Object> noSub = new HashMap<>();
        noSub.put("sub", null);
        assertEquals("E1071", failureFor(mobileRequest(token(noSub))).getStatusCode().code());
    }

    @Test
    void matchingRawNonce_isAccepted() {
        AppleAuthRequest req = mobileRequest(token(Map.of("nonce", "nonce-abc")));
        req.setNonce("nonce-abc");
        assertNotNull(connector().verifyAuth(req).getEmail());
    }

    /** Some Sign in with Apple SDKs hand Apple the hash and keep the raw value. */
    @Test
    void hashedNonce_isAccepted() {
        AppleAuthRequest req = mobileRequest(token(Map.of("nonce", sha256Hex("nonce-abc"))));
        req.setNonce("nonce-abc");
        assertNotNull(connector().verifyAuth(req).getEmail());
    }

    @Test
    void mismatchedNonce_isRejected() {
        AppleAuthRequest req = mobileRequest(token(Map.of("nonce", "minted-for-another-attempt")));
        req.setNonce("nonce-abc");
        assertEquals("E1071", failureFor(req).getStatusCode().code());
    }

    @Test
    void nonceSuppliedButAbsentFromTheToken_isRejected() {
        AppleAuthRequest req = mobileRequest(token(Map.of()));
        req.setNonce("nonce-abc");
        assertEquals("E1071", failureFor(req).getStatusCode().code());
    }

    /** No nonce sent, none in the token: Apple omits the claim when the client never sent one. */
    @Test
    void noNonceEitherSide_isAccepted() {
        assertNotNull(connector().verifyAuth(mobileRequest(token(Map.of()))).getEmail());
    }

    @Test
    void blankIdentityToken_isRejectedBeforeAnyProviderCall() {
        AppleAuthRequest req = new AppleAuthRequest();
        req.setIdentityToken("  ");
        req.setMobileApp(true);
        assertEquals("E1070", failureFor(req).getStatusCode().code());
    }

    @Test
    void noConfiguredClientId_saysTheProviderIsUnavailable() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(json(HttpStatus.OK, Map.of())))
                .build();
        AppleAuthConnector unconfigured = new AppleAuthConnector(webClient, "", "");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> unconfigured.verifyAuth(mobileRequest(token(Map.of()))));
        assertEquals("E1005", ex.getStatusCode().code());
        assertEquals(503, ex.getStatusCode().httpStatus());
    }

    /**
     * A key-set outage must stay Apple's fault. The guard in {@code decodeIdToken} exists for
     * exactly this: without it, the catch-all below relabels a 502 as a bad token and blames
     * the caller for a provider being down.
     */
    @Test
    void appleKeyEndpointDown_isReportedAsApplesFault() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                connector(() -> json(HttpStatus.SERVICE_UNAVAILABLE, Map.of()))
                        .verifyAuth(mobileRequest(token(Map.of()))));

        assertEquals("E1305", ex.getStatusCode().code());
        assertEquals(502, ex.getStatusCode().httpStatus());
    }

    @Test
    void appleKeyEndpointReturnsNoKeys_isReportedAsApplesFault() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                connector(() -> json(HttpStatus.OK, Map.of("something-else", "…")))
                        .verifyAuth(mobileRequest(token(Map.of()))));

        assertEquals("E1305", ex.getStatusCode().code());
    }

    /** Apple publishes a handful of keys and rotates them; refetching per sign-in is waste. */
    @Test
    void applePublicKeyIsCachedAcrossSignIns() {
        int[] fetches = {0};
        AppleAuthConnector connector = connector(() -> {
            fetches[0]++;
            return json(HttpStatus.OK, jwks(KID, (RSAPublicKey) APPLE_KEY.getPublic()));
        });

        connector.verifyAuth(mobileRequest(token(Map.of())));
        connector.verifyAuth(mobileRequest(token(Map.of())));

        assertEquals(1, fetches[0], "second sign-in should reuse the cached key");
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
            throw new IllegalStateException(e);
        }
    }
}
