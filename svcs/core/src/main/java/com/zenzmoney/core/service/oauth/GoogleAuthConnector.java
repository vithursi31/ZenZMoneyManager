package com.zenzmoney.core.service.oauth;

import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.web.dto.GoogleAuthRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class GoogleAuthConnector {

    /**
     * Provider calls are logged here, at the one public entry point, rather than around each
     * outbound request: a failure surfaces as UnauthorizedException and is translated once in
     * GlobalExceptionHandler, but that line cannot say WHICH provider call broke or how slow it was.
     * A provider outage and a misconfigured client id look identical to the caller; they do not look
     * identical here. Never log the token or auth code being verified — it is a live credential.
     */
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthConnector.class);


    private static final String TOKEN_URL    = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";

    /** Both spellings are current; Google returns either depending on the token's age. */
    private static final Set<String> GOOGLE_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final String appId;
    private final String appSecret;
    private final String redirectUrl;
    private final Set<String> validAppIds;

    public GoogleAuthConnector(@Qualifier("oauthWebClient") WebClient webClient,
                               @Value("${zenzmoney.google.app-id:}") String appId,
                               @Value("${zenzmoney.google.app-secret:}") String appSecret,
                               @Value("${zenzmoney.google.app-redirect-url:}") String redirectUrl,
                               @Value("${zenzmoney.google.ios-app-id:}") String iosAppId,
                               @Value("${zenzmoney.google.android-app-id:}") String androidAppId) {
        this.webClient = webClient;
        this.appId = appId;
        this.appSecret = appSecret;
        this.redirectUrl = redirectUrl;

        Set<String> ids = new HashSet<>();
        if (!appId.isBlank()) ids.add(appId);
        if (!iosAppId.isBlank()) ids.add(iosAppId);
        if (!androidAppId.isBlank()) ids.add(androidAppId);
        this.validAppIds = Set.copyOf(ids);
    }

    public GoogleAuthResp verifyAuth(GoogleAuthRequest req) {
        if (req.getType() == null) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_REQUEST_INVALID.with("Missing Google auth type"));
        }
        if (req.getValue() == null || req.getValue().isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_REQUEST_INVALID.with("Missing Google auth value"));
        }
        long startedAt = System.currentTimeMillis();
        try {
            GoogleAuthResp resp = dispatch(req);
            log.debug("Google {} verification succeeded in {}ms",
                    req.getType(), System.currentTimeMillis() - startedAt);
            return resp;
        } catch (RuntimeException e) {
            log.warn("Google {} verification failed in {}ms: {}",
                    req.getType(), System.currentTimeMillis() - startedAt, e.getMessage());
            throw e;
        }
    }

    private GoogleAuthResp dispatch(GoogleAuthRequest req) {
        switch (req.getType()) {
            case IdToken:     return validateIdToken(req.getValue());
            case AccessToken: return validateAccessToken(req.getValue());
            case AuthCode:    return validateIdToken(exchangeCodeForIdToken(req.getValue()));
            default:
                throw new UnauthorizedException(ServiceCodes.SC_OAUTH_REQUEST_INVALID
                        .with("Unsupported Google auth type"));
        }
    }

    private String exchangeCodeForIdToken(String code) {
        if (appId.isBlank() || appSecret.isBlank() || redirectUrl.isBlank()) {
            log.error("Google sign-in is not configured — app id or secret is missing");
            throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                    .with(Msg.OAUTH_UNAVAILABLE, "Google"));
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", appId);
        form.add("client_secret", appSecret);
        form.add("redirect_uri", redirectUrl);
        form.add("grant_type", "authorization_code");

        Map<String, Object> resp = ProviderCall.await(() -> webClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google token exchange");

        if (resp == null || resp.get("id_token") == null) {
            throw new ServiceException(ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR.with("Google did not return id_token"));
        }
        return (String) resp.get("id_token");
    }

    private GoogleAuthResp validateIdToken(String idToken) {
        Map<String, Object> m = ProviderCall.await(() -> webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("oauth2.googleapis.com")
                        .path("/tokeninfo").queryParam("id_token", idToken).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google tokeninfo");

        if (m == null) {
            throw new ServiceException(ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR.with("Google tokeninfo returned empty"));
        }
        requireGoogleIssuer(m.get("iss"));
        requireUnexpired(m.get("exp"));
        requireOurApp(m.get("aud"));

        String email = requireVerifiedEmail(m.get("email"), m.get("email_verified"));

        GoogleAuthResp r = new GoogleAuthResp();
        r.setSubject(asString(m.get("sub")));
        r.setEmail(email);
        r.setFirstName((String) m.get("given_name"));
        r.setLastName((String) m.get("family_name"));
        return r;
    }

    private GoogleAuthResp validateAccessToken(String token) {
        Map<String, Object> ti = ProviderCall.await(() -> webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("oauth2.googleapis.com")
                        .path("/tokeninfo").queryParam("access_token", token).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google tokeninfo");
        if (ti == null) {
            throw new ServiceException(ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR.with("Google tokeninfo returned empty"));
        }
        requireUnexpired(ti.get("exp"));
        requireOurApp(ti.get("aud"));

        Map<String, Object> u = ProviderCall.await(() -> webClient.get()
                .uri(USERINFO_URL)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR, "Google userinfo");
        if (u == null) {
            throw new ServiceException(ServiceCodes.SC_GOOGLE_CONNECTOR_ERROR.with("Google userinfo returned empty"));
        }

        String email = requireVerifiedEmail(u.get("email"), u.get("email_verified"));

        GoogleAuthResp r = new GoogleAuthResp();
        // tokeninfo's sub is the same identity as userinfo's; the access-token response
        // carries it too, so prefer whichever is populated.
        r.setSubject(asString(u.get("sub") != null ? u.get("sub") : ti.get("sub")));
        r.setEmail(email);
        r.setFirstName((String) u.get("given_name"));
        r.setLastName((String) u.get("family_name"));
        return r;
    }

    private void requireOurApp(Object aud) {
        if (aud == null || !validAppIds.contains(aud.toString())) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Google token was issued for a different app"));
        }
    }

    /**
     * Belt to tokeninfo's braces. Google's endpoint only accepts tokens it issued, so this
     * cannot fire today — it exists so that verifying the signature locally instead (which
     * removes the round trip, and is what Google's own docs recommend) does not silently
     * ship without an issuer check. Lenient on absence for the same reason: a response
     * shape that omits {@code iss} must not lock every user out.
     */
    private void requireGoogleIssuer(Object iss) {
        if (iss != null && !GOOGLE_ISSUERS.contains(iss.toString())) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Unexpected Google token issuer"));
        }
    }

    /** {@code exp} comes back as a string of epoch seconds. Absent or unparseable is left to Google. */
    private void requireUnexpired(Object exp) {
        if (exp == null) {
            return;
        }
        try {
            long expiresAtMs = Long.parseLong(exp.toString().trim()) * 1000L;
            if (expiresAtMs <= System.currentTimeMillis()) {
                throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID.with("Google token expired"));
            }
        } catch (NumberFormatException ignored) {
            // Not a number we understand — tokeninfo already refuses expired tokens with a 400,
            // which ProviderCall turns into E1071, so there is nothing to fail closed on here.
        }
    }

    /** Google reports {@code email_verified} as a real boolean on userinfo and as "true" on tokeninfo. */
    private String requireVerifiedEmail(Object emailObj, Object verifiedObj) {
        String email = asString(emailObj);
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_EMAIL_UNVERIFIED);
        }
        if (!Boolean.parseBoolean(String.valueOf(verifiedObj))) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_EMAIL_UNVERIFIED);
        }
        return email;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
