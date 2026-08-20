package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;

public class NotFoundException extends ServiceException {

    public NotFoundException(String message) {
        super(StatusCodes.SC_NOT_FOUND.with(message));
    }

    public NotFoundException(StatusCode statusCode) {
        super(statusCode);
    }
}
