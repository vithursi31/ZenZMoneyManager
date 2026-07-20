package com.zenzmoney.core.util;

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

    public static Optional<String> validate(String password) {
        if (password == null || password.isEmpty())         return Optional.of("Password cannot be empty");
        if (WHITESPACE_PATTERN.matcher(password).matches()) return Optional.of("Password cannot contain spaces");
        if (!SPECIAL_CHAR_PATTERN.matcher(password).find()) return Optional.of("Password must contain a special character");
        if (!LETTER_PATTERN.matcher(password).find())       return Optional.of("Password must contain a letter");
        if (!DIGIT_PATTERN.matcher(password).find())        return Optional.of("Password must contain a digit");
        if (password.length() < MIN_LENGTH)                 return Optional.of("Password must be at least " + MIN_LENGTH + " chars");
        if (password.length() > MAX_LENGTH)                 return Optional.of("Password must be at most "  + MAX_LENGTH + " chars");
        return Optional.empty();
    }
}
