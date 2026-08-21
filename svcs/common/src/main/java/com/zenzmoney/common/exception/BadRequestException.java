package com.zenzmoney.common.exception;

import com.zenzmoney.common.i18n.Message;
import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;

public class BadRequestException extends ServiceException {

    /** English-only. Use the {@link MessageKey} form for anything a user reads. */
    public BadRequestException(String message) {
        super(StatusCodes.SC_BAD_REQUEST.with(message));
    }

    public BadRequestException(MessageKey key, Object... args) {
        super(StatusCodes.SC_BAD_REQUEST.with(key, args));
    }

    public BadRequestException(Message message) {
        super(StatusCodes.SC_BAD_REQUEST.with(message));
    }

    public BadRequestException(StatusCode statusCode) {
        super(statusCode);
    }
}
