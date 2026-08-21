package com.zenzmoney.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zenzmoney.core.i18n.TestMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The send-failure branch used to write the live OTP to the log unconditionally. That branch is
 * reached exactly when SMTP is misconfigured — including in production, where the line then sits in
 * debug.log for the whole retention window as a working credential for the account. These tests pin
 * the gate shut by default.
 */
@ExtendWith(MockitoExtension.class)
class SmtpEmailSenderTest {

    private static final String CODE = "482913";

    @Mock JavaMailSender mailSender;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(SmtpEmailSender.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private SmtpEmailSender senderWithFailingSmtp(boolean logCodeOnSendFailure) {
        doThrow(new MailSendException("no route to mail host"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        return sender(logCodeOnSendFailure);
    }

    private SmtpEmailSender sender(boolean logCodeOnSendFailure) {
        return new SmtpEmailSender(mailSender, TestMessages.resolver(),
                "no-reply@zenzmoney.local", logCodeOnSendFailure);
    }

    private String loggedLines() {
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        return String.join("\n", lines);
    }

    @Test
    void doesNotLogTheVerificationCodeWhenTheFallbackIsDisabled() {
        senderWithFailingSmtp(false).sendVerificationCode("someone@example.com", CODE, Locale.ENGLISH);

        assertFalse(loggedLines().contains(CODE),
                "a live OTP must never reach the log in dev/prd, got: " + loggedLines());
        assertTrue(loggedLines().contains("Failed to send verification code"),
                "the failure itself must still be logged");
    }

    @Test
    void doesNotLogThePasswordResetCodeWhenTheFallbackIsDisabled() {
        senderWithFailingSmtp(false).sendPasswordResetCode("someone@example.com", CODE, Locale.ENGLISH);

        assertFalse(loggedLines().contains(CODE),
                "a live reset code must never reach the log in dev/prd, got: " + loggedLines());
        assertTrue(loggedLines().contains("Failed to send password-reset code"),
                "the failure itself must still be logged");
    }

    @Test
    void logsTheCodeOnlyWhenTheLocalDevFallbackIsExplicitlyEnabled() {
        senderWithFailingSmtp(true).sendVerificationCode("someone@example.com", CODE, Locale.ENGLISH);

        assertTrue(loggedLines().contains(CODE),
                "loc needs the code in the console to finish a flow without SMTP");
        assertTrue(loggedLines().contains("[DEV FALLBACK]"),
                "the line must be labelled so it is obvious it is not a production path");
    }

    /** The code is the point of the mail, so it has to survive whichever bundle rendered the body. */
    @Test
    void theCodeIsInTheBody_inEveryLanguage() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);

        sender(false).sendVerificationCode("someone@example.com", CODE, Locale.forLanguageTag("si"));

        verify(mailSender).send(sent.capture());
        assertTrue(sent.getValue().getText().contains(CODE));
        assertFalse(sent.getValue().getSubject().contains("verification code"),
                "the Sinhala bundle must actually have been used");
    }

    @Test
    void logsNeitherCodeNorFailureOnASuccessfulSend() {
        sender(true).sendVerificationCode("someone@example.com", CODE, Locale.ENGLISH);

        assertFalse(loggedLines().contains(CODE),
                "the fallback must not fire when the send succeeded");
        assertTrue(loggedLines().contains("Sent verification code"));
    }
}
