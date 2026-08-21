package com.zenzmoney.core.service;

import java.util.Locale;

public interface EmailSender {

    void sendVerificationCode(String to, String code, Locale locale);

    void sendPasswordResetCode(String to, String code, Locale locale);
}
