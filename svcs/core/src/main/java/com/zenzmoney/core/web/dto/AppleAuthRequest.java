package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Sign in with Apple, verified from the identity token alone.
 *
 * <p>There is deliberately no {@code email} field. One used to be here, and
 * {@code OAuthLoginService} fell back to it whenever Apple's response carried no email —
 * which made the account identity attacker-controlled: a valid token for the attacker's own
 * Apple ID plus {@code "email": "victim@..."} resolved to the victim's account and minted
 * their tokens. The address now only ever comes from the signature-verified token.
 *
 * <p>{@code authorizationCode} is gone with the token exchange that consumed it (see
 * {@code AppleAuthConnector}); a client may still send it and it is ignored. The name fields
 * stay: Apple reports them to the client on first authorization only, never in the token, and
 * they are used to fill a blank profile — not to decide which account this is.
 */
@Getter
@Setter
public class AppleAuthRequest {

    @NotBlank
    private String identityToken;

    private String givenName;
    private String familyName;

    /** Optional; when present, must match the token's {@code nonce} claim. */
    private String nonce;

    private boolean isMobileApp;
}
