package com.zenzmoney.core.service;

import com.zenzmoney.common.exception.BadRequestException;
import com.zenzmoney.common.exception.TooManyRequestsException;
import com.zenzmoney.core.entity.Verification;
import com.zenzmoney.core.entity.Verification.Purpose;
import com.zenzmoney.core.entity.Verification.Status;
import com.zenzmoney.core.repository.VerificationRepository;
import com.zenzmoney.core.service.ratelimit.RateLimitPolicy;
import com.zenzmoney.core.service.ratelimit.RateLimitResult;
import com.zenzmoney.core.service.ratelimit.RedisRateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

/**
 * Issues and validates one-time 6-digit codes for email verification and
 * password reset. Codes are persisted (see {@link Verification}) so they
 * survive restarts; only one PENDING code exists per (email, purpose) at a
 * time — issuing a new one supersedes the previous.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;

    private static final String RATE_LIMIT_CODE = "E1051";

    /**
     * Per-email throttle on code issuance (register, forgot-password, resends all
     * share it). Mirrors mindmapai's OTP policy: 3 per 10 min, 5 per hour,
     * 10 per day — all enforced together.
     */
    private static final RateLimitPolicy OTP_POLICY = RateLimitPolicy
            .of(3, Duration.ofMinutes(10))
            .and(5, Duration.ofHours(1))
            .and(10, Duration.ofDays(1));

    private final VerificationRepository verificationRepository;
    private final RedisRateLimitService rateLimitService;
    private final long verifyTtlMs;
    private final long resetTtlMs;

    public OtpService(VerificationRepository verificationRepository,
                      RedisRateLimitService rateLimitService,
                      @Value("${zenzmoney.otp.email-verify-ttl-ms:600000}") long verifyTtlMs,
                      @Value("${zenzmoney.otp.password-reset-ttl-ms:600000}") long resetTtlMs) {
        this.verificationRepository = verificationRepository;
        this.rateLimitService = rateLimitService;
        this.verifyTtlMs = verifyTtlMs;
        this.resetTtlMs = resetTtlMs;
    }

    /**
     * Generates a fresh code for {@code email}/{@code purpose}, superseding any
     * previous pending code, and returns the plain code so the caller can email it.
     *
     * <p>Rate-limited per email ({@link #OTP_POLICY}), failing closed: if Redis is
     * unreachable the request is denied rather than allowed.
     */
    @Transactional
    public String issue(String email, Purpose purpose) {
        RateLimitResult rl = rateLimitService.tryConsumeOrDeny("otp:" + email, OTP_POLICY);
        if (!rl.allowed()) {
            log.warn("OTP request rate-limit exceeded for {} (purpose={})", email, purpose);
            throw new TooManyRequestsException(RATE_LIMIT_CODE,
                    "Too many verification code requests. You can request up to 3 codes per 10 minutes, "
                            + "5 per hour, and 10 per day. Please wait before trying again.",
                    rl.retryAfterSeconds());
        }

        supersedePending(email, purpose);

        long ttl = purpose == Purpose.RESET_PASSWORD ? resetTtlMs : verifyTtlMs;

        Verification v = new Verification();
        v.setEmail(email);
        v.setCode(generateCode());
        v.setPurpose(purpose.name());
        v.setStatus(Status.PENDING.name());
        v.setExpiresAt(System.currentTimeMillis() + ttl);
        v.setAttempts(0);
        verificationRepository.save(v);

        return v.getCode();
    }

    /**
     * Validates a submitted code. On success marks the row UTILIZED so it cannot
     * be replayed. Throws {@link BadRequestException} on any failure (unknown,
     * expired, too many attempts, or wrong code).
     */
    @Transactional
    public void verify(String email, String purposeCode, Purpose purpose) {
        if (purposeCode == null || purposeCode.isBlank()) {
            throw new BadRequestException("Verification code is required");
        }

        Verification v = verificationRepository
                .findFirstByEmailAndPurposeAndStatusOrderByCreatedTimeDesc(
                        email, purpose.name(), Status.PENDING.name())
                .orElseThrow(() -> new BadRequestException("No pending verification code. Request a new one."));

        if (v.getExpiresAt() < System.currentTimeMillis()) {
            throw new BadRequestException("Verification code has expired. Request a new one.");
        }

        if (v.getAttempts() >= MAX_ATTEMPTS) {
            throw new BadRequestException("Too many incorrect attempts. Request a new code.");
        }

        if (!v.getCode().equals(purposeCode.trim())) {
            v.setAttempts(v.getAttempts() + 1);
            verificationRepository.save(v);
            throw new BadRequestException("Incorrect verification code");
        }

        v.setStatus(Status.UTILIZED.name());
        verificationRepository.save(v);
    }

    private void supersedePending(String email, Purpose purpose) {
        Optional<Verification> existing = verificationRepository
                .findFirstByEmailAndPurposeAndStatusOrderByCreatedTimeDesc(
                        email, purpose.name(), Status.PENDING.name());
        existing.ifPresent(v -> {
            v.setStatus(Status.UTILIZED.name());
            verificationRepository.save(v);
        });
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
