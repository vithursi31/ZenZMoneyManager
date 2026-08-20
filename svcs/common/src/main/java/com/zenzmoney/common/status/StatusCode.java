package com.zenzmoney.common.status;

import java.util.Objects;

/**
 * One application outcome: its stable wire code, the HTTP status it answers with, and a default
 * message. Equality is on the code alone, so a {@link #with(String)} variant equals its base.
 */
public final class StatusCode {

    private final String code;
    private final int httpStatus;
    private final String description;

    public StatusCode(String code, int httpStatus, String description) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String description() {
        return description;
    }

    /** The same code and status, carrying a call-site message instead of the default. */
    public StatusCode with(String description) {
        return new StatusCode(code, httpStatus, description);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof StatusCode other && code.equals(other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return code + " (" + httpStatus + ") " + description;
    }
}
