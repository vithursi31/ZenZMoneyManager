package com.zenzmoney.core.service;

import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.core.i18n.MessageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private static final MessageKey VERIFICATION_SUBJECT = MessageKey.of("email.verification.subject");
    private static final MessageKey VERIFICATION_BODY = MessageKey.of("email.verification.body");
    private static final MessageKey RESET_SUBJECT = MessageKey.of("email.password-reset.subject");
    private static final MessageKey RESET_BODY = MessageKey.of("email.password-reset.body");

    private final JavaMailSender mailSender;
    private final MessageResolver messages;
    private final String fromEmail;
    private final boolean logCodeOnSendFailure;

    public SmtpEmailSender(JavaMailSender mailSender,
                           MessageResolver messages,
                           @Value("${zenzmoney.app.from-email:no-reply@zenzmoney.local}") String fromEmail,
                           @Value("${zenzmoney.app.log-code-on-send-failure:false}") boolean logCodeOnSendFailure) {
        this.mailSender = mailSender;
        this.messages = messages;
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

    /**
     * The locale is passed in, not read from the request: registration sends this before the user
     * has picked a language, so the caller decides — the signup locale hint for a new account, the
     * stored preference for one that already exists.
     */
    @Override
    public void sendVerificationCode(String to, String code, Locale locale) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(to);
        msg.setSubject(messages.render(VERIFICATION_SUBJECT, locale));
        msg.setText(messages.render(VERIFICATION_BODY, locale, code));
        try {
            mailSender.send(msg);
            log.info("Sent verification code to {}", to);
        } catch (Exception e) {
            log.error("Failed to send verification code to {}: {}", to, e.getMessage());
            logCodeForLocalDev("Verification code", to, code);
        }
    }

    @Override
    public void sendPasswordResetCode(String to, String code, Locale locale) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromEmail);
        msg.setTo(to);
        msg.setSubject(messages.render(RESET_SUBJECT, locale));
        msg.setText(messages.render(RESET_BODY, locale, code));
        try {
            mailSender.send(msg);
            log.info("Sent password-reset code to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password-reset code to {}: {}", to, e.getMessage());
            logCodeForLocalDev("Password-reset code", to, code);
        }
    }
}
