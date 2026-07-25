package com.zenzmoney.core.service;

public interface EmailSender {

    void sendVerificationCode(String to, String code);

    void sendPasswordResetCode(String to, String code);
}
