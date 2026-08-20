package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;

public class ForbiddenException extends ServiceException {

    public ForbiddenException(String message) {
        super(StatusCodes.SC_NOT_AUTHORIZED.with(message));
    }

    public ForbiddenException(StatusCode statusCode) {
        super(statusCode);
    }
}
