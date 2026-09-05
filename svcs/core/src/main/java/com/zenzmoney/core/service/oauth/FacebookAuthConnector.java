package com.zenzmoney.core.service.oauth;

import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.core.web.dto.FacebookAuthRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class FacebookAuthConnector {

    /**
     * Provider calls are logged here, at the one public entry point, rather than around each
     * outbound request: a failure surfaces as UnauthorizedException and is translated once in
     * GlobalExceptionHandler, but that line cannot say WHICH provider call broke or how slow it was.
     * A provider outage and a misconfigured client id look identical to the caller; they do not look
     * identical here. Never log the token or auth code being verified — it is a live credential.
     */
    private static final Logger log = LoggerFactory.getLogger(FacebookAuthConnector.class);


    private static final String GRAPH_HOST = "graph.facebook.com";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final String appId;
    private final String appSecret;
    private final String defaultRedirectUrl;
    private final String graphVersion;

    public FacebookAuthConnector(@Qualifier("oauthWebClient") WebClient webClient,
                                 @Value("${zenzmoney.facebook.app-id:}") String appId,
                                 @Value("${zenzmoney.facebook.app-secret:}") String appSecret,
                                 @Value("${zenzmoney.facebook.app-redirect-url:}") String defaultRedirectUrl,
                                 @Value("${zenzmoney.facebook.graph-version:v19.0}") String graphVersion) {
        this.webClient = webClient;
        this.appId = appId;
        this.appSecret = appSecret;
        this.defaultRedirectUrl = defaultRedirectUrl;
        this.graphVersion = graphVersion;
    }

    public FacebookAuthResp verifyAuth(FacebookAuthRequest req) {
        if (req.getType() == null) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_REQUEST_INVALID.with("Missing Facebook auth type"));
        }
        if (req.getValue() == null || req.getValue().isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_REQUEST_INVALID.with("Missing Facebook auth value"));
        }
        requireConfig();

        long startedAt = System.currentTimeMillis();
        try {
            String accessToken = switch (req.getType()) {
                case AccessToken -> req.getValue();
                case AuthCode    -> exchangeCodeForAccessToken(req.getValue(), req.getRedirectUri());
            };

            verifyAccessToken(accessToken);
            FacebookAuthResp resp = fetchProfile(accessToken);
            log.debug("Facebook {} verification succeeded in {}ms",
                    req.getType(), System.currentTimeMillis() - startedAt);
            return resp;
        } catch (RuntimeException e) {
            log.warn("Facebook {} verification failed in {}ms: {}",
                    req.getType(), System.currentTimeMillis() - startedAt, e.getMessage());
            throw e;
        }
    }

    private void requireConfig() {
        if (appId.isBlank() || appSecret.isBlank()) {
            log.error("Facebook sign-in is not configured — app id or secret is missing");
            throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                    .with(Msg.OAUTH_UNAVAILABLE, "Facebook"));
        }
    }

    private String exchangeCodeForAccessToken(String code, String requestRedirect) {
        String redirect = (requestRedirect != null && !requestRedirect.isBlank())
                ? requestRedirect
                : defaultRedirectUrl;
        if (redirect == null || redirect.isBlank()) {
            log.error("Facebook sign-in is not configured — redirect URI is missing");
            throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                    .with(Msg.OAUTH_UNAVAILABLE, "Facebook"));
        }

        Map<String, Object> resp = ProviderCall.await(() -> webClient.get()
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
                .block(), ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR, "Facebook token exchange");

        if (resp == null || resp.get("access_token") == null) {
            throw new ServiceException(ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR
                    .with("Facebook did not return access_token"));
        }
        return (String) resp.get("access_token");
    }

    @SuppressWarnings("unchecked")
    private void verifyAccessToken(String userAccessToken) {
        String appAccessToken = appId + "|" + appSecret;

        Map<String, Object> resp = ProviderCall.await(() -> webClient.get()
                .uri(b -> b.scheme("https").host(GRAPH_HOST)
                        .path("/debug_token")
                        .queryParam("input_token", userAccessToken)
                        .queryParam("access_token", appAccessToken)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR, "Facebook debug_token");

        if (resp == null || !(resp.get("data") instanceof Map<?, ?> dataMap)) {
            throw new ServiceException(ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR
                    .with("Facebook debug_token returned empty"));
        }
        Map<String, Object> data = (Map<String, Object>) dataMap;

        Object isValid = data.get("is_valid");
        if (!(isValid instanceof Boolean b) || !b) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Facebook access token is not valid"));
        }
        Object tokenAppId = data.get("app_id");
        if (tokenAppId == null || !appId.equals(tokenAppId.toString())) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                    .with("Facebook token issued for a different app"));
        }
    }

    private FacebookAuthResp fetchProfile(String userAccessToken) {
        Map<String, Object> me = ProviderCall.await(() -> webClient.get()
                .uri(b -> b.scheme("https").host(GRAPH_HOST)
                        .path("/" + graphVersion + "/me")
                        .queryParam("fields", "id,email,first_name,last_name")
                        .queryParam("access_token", userAccessToken)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .block(), ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR, "Facebook /me");

        if (me == null) {
            throw new ServiceException(ServiceCodes.SC_FACEBOOK_CONNECTOR_ERROR.with("Facebook /me returned empty"));
        }

        String email = (String) me.get("email");
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException(ServiceCodes.SC_OAUTH_EMAIL_UNVERIFIED.with(
                    "Facebook account has no email; please use another sign-in method"));
        }

        FacebookAuthResp r = new FacebookAuthResp();
        r.setSubject(me.get("id") == null ? null : me.get("id").toString());
        r.setEmail(email);
        r.setFirstName((String) me.get("first_name"));
        r.setLastName((String) me.get("last_name"));
        return r;
    }
}
