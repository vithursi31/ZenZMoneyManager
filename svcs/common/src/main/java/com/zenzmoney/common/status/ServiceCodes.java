package com.zenzmoney.common.status;

/**
 * ZenZ's own status codes, banded by concern. The band map and the client-facing catalogue are in
 * docs/mobile-api-guide.md; a new code belongs in its band and nowhere else.
 */
public interface ServiceCodes extends StatusCodes {

    StatusCode SC_PROVIDER_NOT_CONFIGURED = new StatusCode("E1005", 503, "That option is unavailable right now.");

    StatusCode SC_OTP_RATE_LIMIT_EXCEEDED = new StatusCode("E1051", 429, "Too many verification code requests.");
    StatusCode SC_CHAT_RATE_LIMIT_EXCEEDED = new StatusCode("E1052", 429, "Too many chat messages.");
    StatusCode SC_LOGIN_RATE_LIMIT_EXCEEDED = new StatusCode("E1053", 429, "Too many failed login attempts.");

    StatusCode SC_AUTHORIZATION_MISSING = new StatusCode("E1060", 401, "Authentication required");
    StatusCode SC_TOKEN_INVALID = new StatusCode("E1061", 401, "Invalid token");
    StatusCode SC_TOKEN_EXPIRED = new StatusCode("E1062", 401, "Session expired, please sign in again.");
    StatusCode SC_TOKEN_TYPE_MISMATCH = new StatusCode("E1063", 401, "Wrong token type");
    StatusCode SC_ACCOUNT_NOT_FOUND = new StatusCode("E1064", 401, "Account not found");
    StatusCode SC_ACCOUNT_SUSPENDED = new StatusCode("E1065", 401,
            "Account is not active. Please verify your email.");
    StatusCode SC_ACCOUNT_LOCKED = new StatusCode("E1066", 401, "Account is locked");
    StatusCode SC_INVALID_CREDENTIALS = new StatusCode("E1067", 401, "Invalid email or password");
    StatusCode SC_CURRENT_PASSWORD_INVALID = new StatusCode("E1068", 401, "Current password is incorrect");
    StatusCode SC_ACCOUNT_USES_SOCIAL_LOGIN = new StatusCode("E1069", 401,
            "This account was created with a social login.");

    StatusCode SC_OAUTH_REQUEST_INVALID = new StatusCode("E1070", 401, "Invalid social sign-in request");
    StatusCode SC_OAUTH_TOKEN_INVALID = new StatusCode("E1071", 401, "Social sign-in could not be verified.");
    StatusCode SC_OAUTH_EMAIL_UNVERIFIED = new StatusCode("E1072", 401,
            "Your sign-in provider did not supply a verified email address.");

    StatusCode SC_GOOGLE_CONNECTOR_ERROR = new StatusCode("E1304", 502, "Could not reach Google. Please try again.");
    StatusCode SC_APPLE_CONNECTOR_ERROR = new StatusCode("E1305", 502, "Could not reach Apple. Please try again.");
    StatusCode SC_FACEBOOK_CONNECTOR_ERROR = new StatusCode("E1306", 502,
            "Could not reach Facebook. Please try again.");
}
