package com.zenzmoney.common.exception;

import com.zenzmoney.common.status.StatusCode;

/**
 * A 401. The code is always explicit — there is no default, because "why the caller is not
 * authenticated" is exactly what the client has to branch on.
 */
public class UnauthorizedException extends ServiceException {

    public UnauthorizedException(StatusCode statusCode) {
        super(statusCode);
    }
}
