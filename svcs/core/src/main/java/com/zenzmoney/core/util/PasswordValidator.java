package com.zenzmoney.core.util;

import com.zenzmoney.common.i18n.Message;
import com.zenzmoney.common.i18n.Msg;

import java.util.Optional;
import java.util.regex.Pattern;

public final class PasswordValidator {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;

    private static final Pattern WHITESPACE_PATTERN   = Pattern.compile(".*\\s+.*");
    private static final Pattern LETTER_PATTERN       = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT_PATTERN        = Pattern.compile("\\d");
    private static final Pattern SPECIAL_CHAR_PATTERN =
            Pattern.compile("[!@#$%^&*()\\-\\[\\]{}<>.,;:\"'?+=_~`|/\\\\]");

    private PasswordValidator() {}

    public static Optional<Message> validate(String password) {
        if (password == null || password.isEmpty())         return Optional.of(Message.of(Msg.PASSWORD_EMPTY));
        if (WHITESPACE_PATTERN.matcher(password).matches()) return Optional.of(Message.of(Msg.PASSWORD_WHITESPACE));
        if (!SPECIAL_CHAR_PATTERN.matcher(password).find()) return Optional.of(Message.of(Msg.PASSWORD_NO_SPECIAL));
        if (!LETTER_PATTERN.matcher(password).find())       return Optional.of(Message.of(Msg.PASSWORD_NO_LETTER));
        if (!DIGIT_PATTERN.matcher(password).find())        return Optional.of(Message.of(Msg.PASSWORD_NO_DIGIT));
        if (password.length() < MIN_LENGTH)                 return Optional.of(Message.of(Msg.PASSWORD_TOO_SHORT, MIN_LENGTH));
        if (password.length() > MAX_LENGTH)                 return Optional.of(Message.of(Msg.PASSWORD_TOO_LONG, MAX_LENGTH));
        return Optional.empty();
    }
}
