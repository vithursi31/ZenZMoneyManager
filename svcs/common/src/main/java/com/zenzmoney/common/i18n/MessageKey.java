package com.zenzmoney.common.i18n;

import java.util.Objects;

/**
 * The identity of one user-facing sentence, independent of the {@code errorCode} it travels with.
 * Resolved against the message bundles in {@code core} — this module holds keys, never text.
 */
public final class MessageKey {

    private final String key;

    private MessageKey(String key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    public static MessageKey of(String key) {
        return new MessageKey(key);
    }

    public String key() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof MessageKey other && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
