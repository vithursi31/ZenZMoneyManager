package com.zenzmoney.core.service.ratelimit;

import java.time.Duration;

/**
 * Outcome of a rate-limit check. When denied, {@link #retryAfter()} is the wait
 * before the caller may retry (the tightest denying window's remaining TTL, or
 * the policy's longest window on a fail-closed denial).
 */
public record RateLimitResult(boolean allowed, Duration retryAfter) {

    public static RateLimitResult allow() {
        return new RateLimitResult(true, Duration.ZERO);
    }

    public static RateLimitResult deny(Duration retryAfter) {
        return new RateLimitResult(false, retryAfter);
    }

    public long retryAfterSeconds() {
        long s = retryAfter.getSeconds();
        // Round partial seconds up so a sub-second wait never reports 0.
        return retryAfter.getNano() > 0 ? s + 1 : s;
    }
}
