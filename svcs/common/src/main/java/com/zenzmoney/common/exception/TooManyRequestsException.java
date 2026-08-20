package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;
import lombok.Getter;

/** A 429. The {@code retryAfterSeconds} hint is surfaced as a {@code Retry-After} header. */
@Getter
public class TooManyRequestsException extends ServiceException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(StatusCode statusCode, long retryAfterSeconds) {
        super(statusCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
