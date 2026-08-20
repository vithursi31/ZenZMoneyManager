package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;
import lombok.Getter;

/**
 * Base of every failure the API answers with a status code. The {@link StatusCode} decides both the
 * HTTP status and the {@code errorCode} on the wire; GlobalExceptionHandler translates it once.
 */
@Getter
public class ServiceException extends RuntimeException {

    private final StatusCode statusCode;

    public ServiceException(StatusCode statusCode) {
        super(statusCode.description());
        this.statusCode = statusCode;
    }

    public ServiceException(StatusCode statusCode, Throwable cause) {
        super(statusCode.description(), cause);
        this.statusCode = statusCode;
    }
}
