package com.habit.core.util;

import java.util.Optional;
import java.util.regex.Pattern;

public final class EmailValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private EmailValidator() {}

    public static Optional<String> validate(String email) {
        if (email == null || email.isBlank()) return Optional.of("Email is required");
        if (!EMAIL.matcher(email).matches())  return Optional.of("Invalid email");
        return Optional.empty();
    }
}
