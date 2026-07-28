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
    private final boolean logCodeOnSendFailure;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${zenzmoney.app.from-email:no-reply@zenzmoney.local}") String fromEmail,
                           @Value("${zenzmoney.app.log-code-on-send-failure:false}") boolean logCodeOnSendFailure) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
        this.logCodeOnSendFailure = logCodeOnSendFailure;
    }

    /**
     * Writes the live OTP to the log so local development can complete a flow without working SMTP.
     *
     * <p>Off unless {@code zenzmoney.app.log-code-on-send-failure} is true, which only
     * {@code application-loc.properties} sets. It used to run unconditionally: with SMTP
     * misconfigured in prd — the exact case that reaches this branch — every verification and reset
     * code was written into {@code debug.log}, where it sits for the whole retention window and is a
     * working credential for any account. A code in a log file is a code an attacker can read.
     */
    private void logCodeForLocalDev(String label, String to, String code) {
        if (logCodeOnSendFailure) {
            log.info("[DEV FALLBACK] {} for {}: {}", label, to, code);
        }
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
            logCodeForLocalDev("Verification code", to, code);
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
            logCodeForLocalDev("Password-reset code", to, code);
        }
    }
}
