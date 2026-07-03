package com.habit.core.service.oauth;

import com.habit.common.exception.UnauthorizedException;
import com.habit.core.web.dto.FacebookAuthRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class FacebookAuthConnector {

    private static final String GRAPH_HOST = "graph.facebook.com";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @Value("${habit.facebook.app-id:}")            private String appId;
    @Value("${habit.facebook.app-secret:}")        private String appSecret;
    @Value("${habit.facebook.app-redirect-url:}")  private String defaultRedirectUrl;
    @Value("${habit.facebook.graph-version:v19.0}") private String graphVersion;

    private final WebClient webClient = WebClient.builder().build();

    public FacebookAuthResp verifyAuth(FacebookAuthRequest req) {
        if (req.getType() == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Missing Facebook auth type");
        }
        if (req.getValue() == null || req.getValue().isBlank()) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Missing Facebook auth value");
        }
        requireConfig();

        String accessToken = switch (req.getType()) {
            case AccessToken -> req.getValue();
            case AuthCode    -> exchangeCodeForAccessToken(req.getValue(), req.getRedirectUri());
        };

        verifyAccessToken(accessToken);
        return fetchProfile(accessToken);
    }

    private void requireConfig() {
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new UnauthorizedException("CONFIG_MISSING", "Facebook OAuth is not configured");
        }
    }

    private String exchangeCodeForAccessToken(String code, String requestRedirect) {
        String redirect = (requestRedirect != null && !requestRedirect.isBlank())
                ? requestRedirect
                : defaultRedirectUrl;
        if (redirect == null || redirect.isBlank()) {
            throw new UnauthorizedException("CONFIG_MISSING", "Facebook redirect URI is not configured");
        }

        Map<String, Object> resp = webClient.get()
                .uri(b -> b.scheme("https").host(GRAPH_HOST)
                        .path("/" + graphVersion + "/oauth/access_token")
                        .queryParam("client_id", appId)
                        .queryParam("client_secret", appSecret)
                        .queryParam("redirect_uri", redirect)
                        .queryParam("code", code)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();

        if (resp == null || resp.get("access_token") == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Facebook did not return access_token");
        }
        return (String) resp.get("access_token");
    }

    @SuppressWarnings("unchecked")
    private void verifyAccessToken(String userAccessToken) {
        String appAccessToken = appId + "|" + appSecret;

        Map<String, Object> resp = webClient.get()
                .uri(b -> b.scheme("https").host(GRAPH_HOST)
                        .path("/debug_token")
                        .queryParam("input_token", userAccessToken)
                        .queryParam("access_token", appAccessToken)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();

        if (resp == null || !(resp.get("data") instanceof Map<?, ?> dataMap)) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Facebook debug_token returned empty");
        }
        Map<String, Object> data = (Map<String, Object>) dataMap;

        Object isValid = data.get("is_valid");
        if (!(isValid instanceof Boolean b) || !b) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Facebook access token is not valid");
        }
        Object tokenAppId = data.get("app_id");
        if (tokenAppId == null || !appId.equals(tokenAppId.toString())) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Facebook token issued for a different app");
        }
    }

    private FacebookAuthResp fetchProfile(String userAccessToken) {
        Map<String, Object> me = webClient.get()
                .uri(b -> b.scheme("https").host(GRAPH_HOST)
                        .path("/" + graphVersion + "/me")
                        .queryParam("fields", "id,email,first_name,last_name")
                        .queryParam("access_token", userAccessToken)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block();

        if (me == null) {
            throw new UnauthorizedException("VALIDATION_FAILED", "Facebook /me returned empty");
        }

        String email = (String) me.get("email");
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException("VALIDATION_FAILED",
                    "Facebook account has no email; please use another sign-in method");
        }

        FacebookAuthResp r = new FacebookAuthResp();
        r.setEmail(email);
        r.setFirstName((String) me.get("first_name"));
        r.setLastName((String) me.get("last_name"));
        return r;
    }
}
