package com.zenzmoney.core.service.oauth;

import com.zenzmoney.common.exception.ServiceException;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.common.status.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.function.Supplier;

/**
 * Runs one outbound provider call and turns a transport failure into a typed exception.
 *
 * <p>Without this, a provider's non-2xx leaked out as {@code WebClientResponseException},
 * which no handler in {@code GlobalExceptionHandler} claims and which is not an
 * {@code ErrorResponse} — so it fell to the catch-all. An expired Google token answered
 * <b>500 E1001</b> with an ERROR-level stack trace instead of 401, giving the client
 * nothing to branch on and putting a routine token expiry in the file you read at 2am
 * looking for defects.
 *
 * <p>The split is by who is at fault, and there are three answers. A provider 4xx normally
 * means the provider rejected the credential we forwarded — the caller's token is stale or
 * forged, and retrying will not help ({@code E1071}, 401). A 5xx, a refused connection, or
 * the response timeout from {@code OAuthHttpConfig} means the provider is unwell — the caller
 * did nothing wrong and a retry may work ({@code E130x}, 502). And a 4xx naming
 * {@code invalid_client} is neither: the provider refused <em>our</em> configured credentials,
 * which fails every sign-in through that path until someone fixes the config
 * ({@code E1005}, 503).
 */
final class ProviderCall {

    private static final Logger log = LoggerFactory.getLogger(ProviderCall.class);

    private ProviderCall() {
    }

    /**
     * @param what          which call, for the log — e.g. {@code "Google tokeninfo"}
     * @param connectorError the provider's own {@code E130x} code, used when the provider
     *                       rather than the caller is at fault
     */
    static <T> T await(Supplier<T> call, StatusCode connectorError, String what) {
        try {
            return call.get();
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            String body = summarise(e);
            if (e.getStatusCode().is4xxClientError()) {
                if (isOurCredentialsFault(e.getResponseBodyAsString())) {
                    // Our client id or secret is wrong, not the caller's token. Every sign-in
                    // through this path fails, so telling the user their token is invalid sends
                    // the whole investigation the wrong way.
                    log.error("{} refused OUR credentials — check the configured client id and "
                            + "secret: {} {}", what, status, body);
                    throw new ServiceException(ServiceCodes.SC_PROVIDER_NOT_CONFIGURED
                            .with(what + " refused our client credentials: " + status + " " + body));
                }
                throw new UnauthorizedException(ServiceCodes.SC_OAUTH_TOKEN_INVALID
                        .with(what + " rejected the credential: " + status + " " + body));
            }
            throw new ServiceException(connectorError
                    .with(what + " failed: " + status + " " + body));
        } catch (WebClientRequestException e) {
            // Connect refused, DNS failure, or the response timeout — the request never
            // got an answer, so there is no status to read.
            throw new ServiceException(connectorError.with(what + " unreachable: " + e.getMessage()));
        }
    }

    /**
     * RFC 6749 §5.2 names the two rejections that are about <em>us</em> rather than the caller:
     * the client id/secret pair is wrong, or that client is not allowed this grant. Matched on
     * the machine code, which is what these bodies are required to carry — and on the whole
     * body, not the capped summary, since a verbose provider can push the code past the cap.
     */
    private static boolean isOurCredentialsFault(String body) {
        return body != null
                && (body.contains("invalid_client") || body.contains("unauthorized_client"));
    }

    /**
     * A provider's error body carries the machine code that separates a stale token
     * ({@code invalid_grant}) from a misconfigured secret ({@code invalid_client}) — the
     * status alone cannot, and that is exactly the distinction worth having in the log.
     * These bodies echo the failure, never the credential that was sent; capped regardless
     * so a verbose provider cannot flood the file. Diagnostic only: it is English and
     * log-only, and never reaches the client.
     */
    private static String summarise(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
