package com.zenzmoney.common.status;

import com.zenzmoney.common.i18n.Message;
import com.zenzmoney.common.i18n.MessageKey;

import java.util.Objects;

/**
 * One application outcome: its stable wire code, the HTTP status it answers with, and a default
 * message. Equality is on the code alone, so a variant equals its base.
 *
 * <p>Two kinds of message ride along, and they are not interchangeable:
 * <ul>
 *   <li>{@link #with(MessageKey, Object...)} — what the <em>user</em> reads. Resolved against the
 *       message bundles in the caller's language at the boundary.
 *   <li>{@link #with(String)} — a call-site <em>diagnostic</em>. English, log-only; it never
 *       reaches the client, so it may carry provider or library detail.
 * </ul>
 */
public final class StatusCode {

    private static final Object[] NO_ARGS = new Object[0];

    private final String code;
    private final int httpStatus;
    private final String description;
    private final String detail;
    private final MessageKey messageKey;
    private final Object[] args;

    public StatusCode(String code, int httpStatus, String description) {
        this(code, httpStatus, description, null, null, NO_ARGS);
    }

    private StatusCode(String code, int httpStatus, String description,
                       String detail, MessageKey messageKey, Object[] args) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.description = description;
        this.detail = detail;
        this.messageKey = messageKey;
        this.args = args == null ? NO_ARGS : args;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** The registry's English default. Never overwritten by a call site. */
    public String description() {
        return description;
    }

    /** The call-site diagnostic, or null. English, for the log only. */
    public String detail() {
        return detail;
    }

    public MessageKey messageKey() {
        return messageKey;
    }

    public Object[] args() {
        return args.length == 0 ? NO_ARGS : args.clone();
    }

    /** The most specific English text available — what a log line and a stack trace should carry. */
    public String logMessage() {
        return detail != null ? detail : description;
    }

    /**
     * The same code and status, carrying a call-site diagnostic. <b>Not shown to the user</b> — the
     * response text comes from {@link #messageKey()} or from the code's own bundle entry.
     */
    public StatusCode with(String detail) {
        return new StatusCode(code, httpStatus, description, detail, messageKey, args);
    }

    /** The same code and status, answering with a localisable message. */
    public StatusCode with(MessageKey key, Object... args) {
        return new StatusCode(code, httpStatus, description, detail, key, args);
    }

    /** The same code and status, answering with a localisable message. */
    public StatusCode with(Message message) {
        return with(message.key(), message.args());
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
        return code + " (" + httpStatus + ") " + logMessage();
    }
}
