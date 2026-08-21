package com.zenzmoney.common.exception;

import com.zenzmoney.common.i18n.Message;
import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;

public class NotFoundException extends ServiceException {

    /** English-only. Use the {@link MessageKey} form for anything a user reads. */
    public NotFoundException(String message) {
        super(StatusCodes.SC_NOT_FOUND.with(message));
    }

    public NotFoundException(MessageKey key, Object... args) {
        super(StatusCodes.SC_NOT_FOUND.with(key, args));
    }

    public NotFoundException(Message message) {
        super(StatusCodes.SC_NOT_FOUND.with(message));
    }

    public NotFoundException(StatusCode statusCode) {
        super(statusCode);
    }
}
