package com.zenzmoney.core.service;

public interface EmailSender {

    void sendVerificationLink(String to, String link);

    void sendPasswordResetLink(String to, String link);
}
