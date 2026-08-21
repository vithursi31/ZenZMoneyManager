package com.zenzmoney.common.i18n;

import java.util.Arrays;

/** A {@link MessageKey} together with the arguments its placeholders take. */
public final class Message {

    private static final Object[] NO_ARGS = new Object[0];

    private final MessageKey key;
    private final Object[] args;

    private Message(MessageKey key, Object[] args) {
        this.key = key;
        this.args = args == null || args.length == 0 ? NO_ARGS : args.clone();
    }

    public static Message of(MessageKey key, Object... args) {
        return new Message(key, args);
    }

    public MessageKey key() {
        return key;
    }

    public Object[] args() {
        return args.length == 0 ? NO_ARGS : args.clone();
    }

    @Override
    public String toString() {
        return args.length == 0 ? key.key() : key.key() + Arrays.toString(args);
    }
}
