package com.zenzmoney.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${zenzmoney.app.from-email:no-reply@zenzmoney.local}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendVerificationCode(String to, String code) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(to);
        msg.setSubject("Your ZenZ Money Manager verification code");
        msg.setText("Welcome to ZenZ Money Manager!\n\n"
                + "Your email verification code is:\n\n"
                + "    " + code + "\n\n"
                + "Enter it in the app to finish setting up your account (valid for 10 minutes).\n\n"
                + "If you did not create this account, you can ignore this message.\n");
        try {
            mailSender.send(msg);
            log.info("Sent verification code to {}", to);
        } catch (Exception e) {
            log.error("Failed to send verification code to {}: {}", to, e.getMessage());
            log.info("[DEV FALLBACK] Verification code for {}: {}", to, code);
        }
    }

    @Override
    public void sendPasswordResetCode(String to, String code) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(to);
        msg.setSubject("Your ZenZ Money Manager password reset code");
        msg.setText("We received a request to reset your ZenZ Money Manager password.\n\n"
                + "Your password reset code is:\n\n"
                + "    " + code + "\n\n"
                + "Enter it in the app along with your new password (valid for 10 minutes).\n\n"
                + "If you did not request this, you can safely ignore this message — your password will not change.\n");
        try {
            mailSender.send(msg);
            log.info("Sent password-reset code to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password-reset code to {}: {}", to, e.getMessage());
            log.info("[DEV FALLBACK] Password-reset code for {}: {}", to, code);
        }
    }
}
