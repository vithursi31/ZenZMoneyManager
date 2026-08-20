package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;

public class BadRequestException extends ServiceException {

    public BadRequestException(String message) {
        super(StatusCodes.SC_BAD_REQUEST.with(message));
    }

    public BadRequestException(StatusCode statusCode) {
        super(statusCode);
    }
}
