package com.zenzmoney.common.exception;

import com.zenzmoney.common.i18n.Message;
import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;

public class ForbiddenException extends ServiceException {

    /** English-only. Use the {@link MessageKey} form for anything a user reads. */
    public ForbiddenException(String message) {
        super(StatusCodes.SC_NOT_AUTHORIZED.with(message));
    }

    public ForbiddenException(MessageKey key, Object... args) {
        super(StatusCodes.SC_NOT_AUTHORIZED.with(key, args));
    }

    public ForbiddenException(Message message) {
        super(StatusCodes.SC_NOT_AUTHORIZED.with(message));
    }

    public ForbiddenException(StatusCode statusCode) {
        super(statusCode);
    }
}
