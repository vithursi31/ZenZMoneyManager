package com.zenzmoney.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zenzmoney.common.exception.UnauthorizedException;
import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.service.JwtTokenService;
import com.zenzmoney.core.service.LoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Guards the audit trail itself, not just the auth logic: an audit line that silently stops being
 * emitted, or that starts carrying a credential, is a defect you only discover when you need the
 * file. The assertions here are about which channel the line lands on and what it must never
 * contain.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Mock UserRepository userRepository;
    @Mock JwtTokenService jwtTokenService;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger auditLogger;
    private LoginService loginService;

    @BeforeEach
    void setUp() {
        // Capture the "audit" channel by name — the same name logback-spring.xml routes to
        // audit.log. If AppLog.AUDIT were ever repointed at a class logger, this test fails.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        auditLogger = context.getLogger("audit");
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        auditLogger.addAppender(appender);
        auditLogger.setLevel(Level.INFO);

        // A real encoder, so a hash is genuinely a hash and the "never log the secret" assertion
        // is not trivially satisfied by a stub returning the plaintext.
        loginService = new LoginService(userRepository,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(),
                jwtTokenService);
        when(jwtTokenService.generateAccessToken(anyString())).thenReturn("access-token");
        when(jwtTokenService.generateRefreshToken(anyString())).thenReturn("refresh-token");
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
        appender.stop();
    }

    private User activeUser() {
        User u = new User();
        u.setId("user-1");
        u.setEmail("someone@example.com");
        u.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode(PASSWORD));
        u.setAuthMode("password");
        u.setStatus("active");
        u.setLocked(false);
        u.setLoginAttempts(0);
        u.setSystemGeneratedPassword(false);
        return u;
    }

    private List<String> auditLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void successfulLoginIsAudited() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        loginService.login("someone@example.com", PASSWORD);

        assertTrue(auditLines().stream().anyMatch(l -> l.contains("Login succeeded")
                        && l.contains("someone@example.com")),
                "expected a login-success audit line, got: " + auditLines());
    }

    @Test
    void failedLoginIsAuditedWithTheAttemptCount() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(UnauthorizedException.class,
                () -> loginService.login("someone@example.com", "wrong-password"));

        assertTrue(auditLines().stream().anyMatch(l -> l.contains("Login denied")
                        && l.contains("wrong password")
                        && l.contains("attempt 1")),
                "expected a failed-login audit line, got: " + auditLines());
    }

    @Test
    void lockoutIsAuditedAtWarn() {
        User user = activeUser();
        user.setLoginAttempts(5);
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(UnauthorizedException.class,
                () -> loginService.login("someone@example.com", "wrong-password"));

        ILoggingEvent lockLine = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("locked"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a lockout audit line, got: " + auditLines()));
        assertEquals(Level.WARN, lockLine.getLevel(),
                "a lockout is the line you grep for after an incident — it must outrank INFO");
    }

    @Test
    void loginAuditNeverContainsThePasswordOrTheIssuedTokens() {
        when(userRepository.findByEmail("someone@example.com")).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        loginService.login("someone@example.com", PASSWORD);
        assertThrows(UnauthorizedException.class,
                () -> loginService.login("someone@example.com", "wrong-password"));

        String all = String.join("\n", auditLines());
        assertFalse(all.contains(PASSWORD), "audit.log must never carry a password");
        assertFalse(all.contains("wrong-password"), "audit.log must never carry a submitted password");
        assertFalse(all.contains("access-token"), "audit.log must never carry an issued token");
        assertFalse(all.contains("refresh-token"), "audit.log must never carry an issued token");
        assertFalse(all.contains("$2a$"), "audit.log must never carry a bcrypt hash");
    }
}
