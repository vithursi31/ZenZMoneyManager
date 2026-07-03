package com.habit.core.web.dto;

import lombok.Getter;

@Getter
public class RegisterResponse {
    private final String userId;
    private final String email;
    private final String message;

    public RegisterResponse(String userId, String email, String message) {
        this.userId = userId;
        this.email = email;
        this.message = message;
    }
}
