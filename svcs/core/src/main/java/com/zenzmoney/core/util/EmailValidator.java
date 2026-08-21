package com.zenzmoney.core.util;

import com.zenzmoney.common.i18n.Message;
import com.zenzmoney.common.i18n.Msg;

import java.util.Optional;
import java.util.regex.Pattern;

public final class EmailValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private EmailValidator() {}

    public static Optional<Message> validate(String email) {
        if (email == null || email.isBlank()) return Optional.of(Message.of(Msg.EMAIL_REQUIRED));
        if (!EMAIL.matcher(email).matches())  return Optional.of(Message.of(Msg.EMAIL_INVALID));
        return Optional.empty();
    }
}
