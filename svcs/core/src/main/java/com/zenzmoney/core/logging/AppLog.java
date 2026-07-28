package com.zenzmoney.core.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The named log channels declared in {@code logback-spring.xml}, each routed to its own file.
 *
 * <p>Held as constants rather than repeating {@code LoggerFactory.getLogger("audit")} at every call
 * site: a typo in that string does not fail, it silently creates a brand-new logger that falls
 * through to root, so the line quietly lands in the wrong file. Referencing a constant makes the
 * channel set a closed list that the compiler checks.
 *
 * <p>Class-scoped loggers stay as they are — {@code LoggerFactory.getLogger(Foo.class)} lands under
 * {@code com.zenzmoney} and therefore in {@code debug.log}/{@code info.log}/{@code error.log}. Use a
 * channel here only when the lines are worth reading on their own, away from request-level noise.
 */
public final class AppLog {

    /**
     * Security-relevant events: registration, email verification, login success/failure, OTP
     * issuance and denial, password reset. Retained for a year — this is the file an incident is
     * reconstructed from, so never write a credential, token, or OTP code into it.
     */
    public static final Logger AUDIT = LoggerFactory.getLogger("audit");

    /** Background job runs — what each scheduled pass did, and what it skipped. */
    public static final Logger SCHEDULER = LoggerFactory.getLogger("scheduler");

    /** LLM/extraction calls (F-1.9a): prompts sent, latency, and parse failures. */
    public static final Logger LLM = LoggerFactory.getLogger("llm");

    private AppLog() {}
}
