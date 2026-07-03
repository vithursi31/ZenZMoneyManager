package com.habit.core.service.oauth;

import com.habit.common.exception.UnauthorizedException;
import com.habit.core.web.dto.GoogleAuthRequest;
import jakarta.annotation.PostConstruct;
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

    private static final String TOKEN_URL     = "https://oauth2.googleapis.com/token";
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";
    private static final String USERINFO_URL  = "https://www.googleapis.com/oauth2/v3/userinfo";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @Value("${habit.google.app-id:}")            private String appId;
    @Value("${habit.google.app-secret:}")        private String appSecret;
    @Value("${habit.google.app-redirect-url:}")  private String redirectUrl;
    @Value("${habit.google.ios-app-id:}")        private String iosAppId;
    @Value("${habit.google.android-app-id:}")    private String androidAppId;

    private final WebClient webClient = WebClient.builder().build();
    private Set<String> validAppIds;

    @PostConstruct
    void init() {
        validAppIds = new HashSet<>();
        if (!appId.isBlank())        validAppIds.add(appId);
        if (!iosAppId.isBlank())     validAppIds.add(iosAppId);
        if (!androidAppId.isBlank()) validAppIds.add(androidAppId);
    }

    public GoogleAuthResp verifyAuth(GoogleAuthRequest req) {
        if (req.getType() == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Missing Google auth type");
        }
        switch (req.getType()) {
            case IdToken:     return validateIdToken(req.getValue());
            case AccessToken: return validateAccessToken(req.getValue());
            case AuthCode:    return validateIdToken(exchangeCodeForIdToken(req.getValue()));
            default:
                throw new UnauthorizedException("VALIDATION_FAILED", "Unsupported Google auth type");
        }
    }

    private String exchangeCodeForIdToken(String code) {
        if (appId.isBlank() || appSecret.isBlank() || redirectUrl.isBlank()) {
            throw new UnauthorizedException("CONFIG_MISSING", "Google OAuth is not configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", appId);
        form.add("client_secret", appSecret);
        form.add("redirect_uri", redirectUrl);
        form.add("grant_type", "authorization_code");

        Map<String, Object> resp = webClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();

        if (resp == null || resp.get("id_token") == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google did not return id_token");
        }
        return (String) resp.get("id_token");
    }

    private GoogleAuthResp validateIdToken(String idToken) {
        Map<String, Object> m = webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("oauth2.googleapis.com")
                        .path("/tokeninfo").queryParam("id_token", idToken).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();

        if (m == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google tokeninfo returned empty");
        }
        Object audObj = m.get("aud");
        Object emailVerifiedObj = m.get("email_verified");
        Object emailObj = m.get("email");

        String email = emailObj == null ? null : emailObj.toString();
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google email missing");
        }
        if (!Boolean.parseBoolean(String.valueOf(emailVerifiedObj))) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google email not verified");
        }
        if (!validAppIds.contains(audObj)) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Invalid Google client id");
        }

        GoogleAuthResp r = new GoogleAuthResp();
        r.setEmail(email);
        r.setFirstName((String) m.get("given_name"));
        r.setLastName((String) m.get("family_name"));
        return r;
    }

    private GoogleAuthResp validateAccessToken(String token) {
        Map<String, Object> ti = webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("oauth2.googleapis.com")
                        .path("/tokeninfo").queryParam("access_token", token).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();
        if (ti == null || !validAppIds.contains(ti.get("aud"))) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Invalid Google client id");
        }

        Map<String, Object> u = webClient.get()
                .uri(USERINFO_URL)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();
        if (u == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google userinfo returned empty");
        }

        String email = (String) u.get("email");
        Object verified = u.get("email_verified");
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google email missing");
        }
        if (!(verified instanceof Boolean b) || !b) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Google email not verified");
        }

        GoogleAuthResp r = new GoogleAuthResp();
        r.setEmail(email);
        r.setFirstName((String) u.get("given_name"));
        r.setLastName((String) u.get("family_name"));
        return r;
    }
}
