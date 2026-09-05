package com.zenzmoney.core.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.core.web.dto.GoogleAuthRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The connector's job is to answer one question — whose verified email is this, if anyone's —
 * and to be honest about who is at fault when it cannot. Google's tokeninfo endpoint rejects a
 * bad token with a 400, so the {@code tokeninfoRejects…} cases are the ones that used to come
 * back as a 500.
 */
class GoogleAuthConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String WEB_ID     = "web-client.apps.googleusercontent.com";
    private static final String ANDROID_ID = "android-client.apps.googleusercontent.com";

    /** Routes by path so one stub can serve tokeninfo, userinfo, and the token exchange. */
    private GoogleAuthConnector connector(Function<String, ClientResponse> byPath) {
        ExchangeFunction exchange = request -> Mono.just(byPath.apply(request.url().getPath()));
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        return new GoogleAuthConnector(webClient, WEB_ID, "web-secret",
                "https://zenz.example/callback", "", ANDROID_ID);
    }

    /** No client ids configured at all — the AuthCode path needs them to talk to Google. */
    private GoogleAuthConnector unconfiguredConnector() {
        ExchangeFunction exchange = request -> Mono.just(json(HttpStatus.OK, Map.of()));
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        return new GoogleAuthConnector(webClient, "", "", "", "", "");
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

    /** Shaped like Google's tokeninfo: strings throughout, including the booleans. */
    private static Map<String, Object> tokeninfo(Map<String, Object> overrides) {
        Map<String, Object> m = new HashMap<>();
        m.put("iss", "https://accounts.google.com");
        m.put("aud", WEB_ID);
        m.put("sub", "108124562");
        m.put("exp", String.valueOf(System.currentTimeMillis() / 1000 + 600));
        m.put("email", "someone@example.com");
        m.put("email_verified", "true");
        m.put("given_name", "Given");
        m.put("family_name", "Family");
        m.putAll(overrides);
        return m;
    }

    private static GoogleAuthRequest idToken() {
        GoogleAuthRequest r = new GoogleAuthRequest();
        r.setType(GoogleAuthRequest.GoogleAuthType.IdToken);
        r.setValue("an-id-token");
        return r;
    }

    private ServiceException failureFor(Map<String, Object> tokeninfoBody) {
        return assertThrows(ServiceException.class, () ->
                connector(path -> json(HttpStatus.OK, tokeninfoBody)).verifyAuth(idToken()));
    }

    @Test
    void idToken_returnsTheVerifiedIdentity() {
        GoogleAuthResp resp = connector(path -> json(HttpStatus.OK, tokeninfo(Map.of())))
                .verifyAuth(idToken());

        assertEquals("someone@example.com", resp.getEmail());
        assertEquals("108124562", resp.getSubject(), "the stable identity, not the email");
        assertEquals("Given", resp.getFirstName());
        assertEquals("Family", resp.getLastName());
    }

    @Test
    void idToken_acceptsAnyOfOurConfiguredClientIds() {
        GoogleAuthResp resp = connector(path -> json(HttpStatus.OK, tokeninfo(Map.of("aud", ANDROID_ID))))
                .verifyAuth(idToken());
        assertEquals("someone@example.com", resp.getEmail());
    }

    @Test
    void tokenForAnotherApp_isRejected() {
        assertEquals("E1071", failureFor(tokeninfo(Map.of("aud", "someone-elses.apps.googleusercontent.com")))
                .getStatusCode().code());
    }

    @Test
    void tokenWithNoAudience_isRejected() {
        Map<String, Object> body = tokeninfo(Map.of());
        body.remove("aud");
        assertEquals("E1071", failureFor(body).getStatusCode().code());
    }

    @Test
    void unverifiedEmail_isRejected() {
        assertEquals("E1072", failureFor(tokeninfo(Map.of("email_verified", "false")))
                .getStatusCode().code());
    }

    @Test
    void missingEmail_isRejected() {
        Map<String, Object> body = tokeninfo(Map.of());
        body.remove("email");
        assertEquals("E1072", failureFor(body).getStatusCode().code());
    }

    /** Absent {@code email_verified} fails closed: an unverified Google address is a real state. */
    @Test
    void absentEmailVerified_isRejected() {
        Map<String, Object> body = tokeninfo(Map.of());
        body.remove("email_verified");
        assertEquals("E1072", failureFor(body).getStatusCode().code());
    }

    @Test
    void expiredToken_isRejected() {
        assertEquals("E1071", failureFor(tokeninfo(Map.of(
                "exp", String.valueOf(System.currentTimeMillis() / 1000 - 60))))
                .getStatusCode().code());
    }

    @Test
    void unexpectedIssuer_isRejected() {
        assertEquals("E1071", failureFor(tokeninfo(Map.of("iss", "https://accounts.evil.example")))
                .getStatusCode().code());
    }

    @Test
    void bareHostIssuer_isAccepted() {
        GoogleAuthResp resp = connector(path -> json(HttpStatus.OK, tokeninfo(Map.of("iss", "accounts.google.com"))))
                .verifyAuth(idToken());
        assertEquals("someone@example.com", resp.getEmail());
    }

    /**
     * The regression. Google answers a stale or forged id_token with a 400, which used to leave
     * the connector as a {@code WebClientResponseException} and reach the client as 500 E1001.
     */
    @Test
    void tokeninfoRejectsTheToken_is401NotAServerError() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                connector(path -> json(HttpStatus.BAD_REQUEST, Map.of("error_description", "Invalid Value")))
                        .verifyAuth(idToken()));

        assertEquals("E1071", ex.getStatusCode().code());
        assertEquals(401, ex.getStatusCode().httpStatus());
    }

    @Test
    void tokeninfoIsDown_isReportedAsGooglesFault() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                connector(path -> json(HttpStatus.SERVICE_UNAVAILABLE, Map.of())).verifyAuth(idToken()));

        assertEquals("E1304", ex.getStatusCode().code());
        assertEquals(502, ex.getStatusCode().httpStatus());
    }

    @Test
    void missingType_isRejectedBeforeAnyProviderCall() {
        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setValue("something");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connector(path -> json(HttpStatus.OK, Map.of())).verifyAuth(req));
        assertEquals("E1070", ex.getStatusCode().code());
    }

    @Test
    void blankValue_isRejectedBeforeAnyProviderCall() {
        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.IdToken);
        req.setValue("   ");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connector(path -> json(HttpStatus.OK, Map.of())).verifyAuth(req));
        assertEquals("E1070", ex.getStatusCode().code());
    }

    @Test
    void authCodeWithoutConfiguration_saysTheProviderIsUnavailable() {
        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.AuthCode);
        req.setValue("a-code");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> unconfiguredConnector().verifyAuth(req));
        assertEquals("E1005", ex.getStatusCode().code());
        assertEquals(503, ex.getStatusCode().httpStatus());
    }

    @Test
    void authCode_isExchangedThenVerified() {
        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.AuthCode);
        req.setValue("a-code");

        GoogleAuthResp resp = connector(path -> path.endsWith("/token")
                ? json(HttpStatus.OK, Map.of("id_token", "the-id-token"))
                : json(HttpStatus.OK, tokeninfo(Map.of()))).verifyAuth(req);

        assertEquals("someone@example.com", resp.getEmail());
    }

    @Test
    void accessToken_readsTheProfileFromUserinfo() {
        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.AccessToken);
        req.setValue("an-access-token");

        GoogleAuthResp resp = connector(path -> path.contains("tokeninfo")
                ? json(HttpStatus.OK, Map.of("aud", WEB_ID, "sub", "108124562",
                        "exp", String.valueOf(System.currentTimeMillis() / 1000 + 600)))
                : json(HttpStatus.OK, Map.of("sub", "108124562", "email", "someone@example.com",
                        "email_verified", true, "given_name", "Given", "family_name", "Family")))
                .verifyAuth(req);

        assertEquals("someone@example.com", resp.getEmail());
        assertEquals("108124562", resp.getSubject());
    }

    /** userinfo reports a real boolean, unlike tokeninfo's string — both must be honoured. */
    @Test
    void accessTokenWithUnverifiedEmail_isRejected() {
        GoogleAuthRequest req = new GoogleAuthRequest();
        req.setType(GoogleAuthRequest.GoogleAuthType.AccessToken);
        req.setValue("an-access-token");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                connector(path -> path.contains("tokeninfo")
                        ? json(HttpStatus.OK, Map.of("aud", WEB_ID))
                        : json(HttpStatus.OK, Map.of("email", "someone@example.com",
                                "email_verified", false)))
                        .verifyAuth(req));

        assertEquals("E1072", ex.getStatusCode().code());
    }
}
