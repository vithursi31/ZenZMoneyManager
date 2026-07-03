package com.habit.core.service;

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
                           @Value("${habit.app.from-email:no-reply@zenzhabits.local}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendVerificationLink(String to, String link) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(to);
        msg.setSubject("Verify your ZenZHabits email");
        msg.setText("Welcome to ZenZHabits!\n\n"
                + "Please verify your email by clicking the link below (valid for 24 hours):\n\n"
                + link + "\n\n"
                + "If you did not create this account, you can ignore this message.\n");
        try {
            mailSender.send(msg);
            log.info("Sent verification email to {}", to);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", to, e.getMessage());
            log.info("[DEV FALLBACK] Verification link for {}: {}", to, link);
        }
    }

    @Override
    public void sendPasswordResetLink(String to, String link) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(to);
        msg.setSubject("Reset your ZenZHabits password");
        msg.setText("We received a request to reset your ZenZHabits password.\n\n"
                + "Click the link below to choose a new password (valid for 30 minutes):\n\n"
                + link + "\n\n"
                + "If you did not request this, you can safely ignore this message — your password will not change.\n");
        try {
            mailSender.send(msg);
            log.info("Sent password-reset email to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password-reset email to {}: {}", to, e.getMessage());
            log.info("[DEV FALLBACK] Password-reset link for {}: {}", to, link);
        }
    }
}
