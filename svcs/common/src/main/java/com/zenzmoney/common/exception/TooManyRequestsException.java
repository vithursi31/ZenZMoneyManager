package com.zenzmoney.common.exception;

import lombok.Getter;

/**
 * Thrown when a caller exceeds a rate limit. Maps to HTTP 429; the
 * {@code retryAfterSeconds} hint is surfaced as a {@code Retry-After} header.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final String errorCode;
    private final long retryAfterSeconds;

    public TooManyRequestsException(String errorCode, String message, long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
