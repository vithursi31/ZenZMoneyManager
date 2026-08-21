package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;
import lombok.Getter;

/**
 * Base of every failure the API answers with a status code. The {@link StatusCode} decides both the
 * HTTP status and the {@code errorCode} on the wire; GlobalExceptionHandler translates it once, and
 * renders the user-facing text in the caller's language there.
 *
 * <p>{@code getMessage()} is always English — it is what a stack trace carries.
 */
@Getter
public class ServiceException extends RuntimeException {

    private final StatusCode statusCode;

    public ServiceException(StatusCode statusCode) {
        super(describe(statusCode));
        this.statusCode = statusCode;
    }

    public ServiceException(StatusCode statusCode, Throwable cause) {
        super(describe(statusCode), cause);
        this.statusCode = statusCode;
    }

    /** The most specific English identity available: a diagnostic, else the message key, else the default. */
    private static String describe(StatusCode sc) {
        if (sc.detail() != null) {
            return sc.detail();
        }
        return sc.messageKey() != null ? sc.messageKey().key() : sc.description();
    }
}
