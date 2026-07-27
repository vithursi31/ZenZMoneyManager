package com.zenzmoney.core.service.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A rate-limit policy composed of one or more fixed windows. Every window must
 * admit a request for it to be allowed (e.g. "3 per 10 min AND 5 per hour").
 *
 * <p>Fixed-window semantics mean up to ~2× a window's limit can pass across a
 * window boundary; acceptable for abuse-throttling of OTP issuance.
 */
public final class RateLimitPolicy {

    /** A single window: at most {@code limit} requests per {@code period}. */
    public record Window(int limit, Duration period) {
        public Window {
            if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("period must be positive");
            }
        }
    }

    private final List<Window> windows;

    private RateLimitPolicy(List<Window> windows) {
        this.windows = Collections.unmodifiableList(windows);
    }

    public static RateLimitPolicy of(int limit, Duration period) {
        List<Window> ws = new ArrayList<>();
        ws.add(new Window(limit, period));
        return new RateLimitPolicy(ws);
    }

    public RateLimitPolicy and(int limit, Duration period) {
        List<Window> ws = new ArrayList<>(windows);
        ws.add(new Window(limit, period));
        return new RateLimitPolicy(ws);
    }

    public List<Window> windows() {
        return windows;
    }

    /** The longest window period — used as the retry-after hint on fail-closed denials. */
    public Duration longestPeriod() {
        return windows.stream().map(Window::period).max(Duration::compareTo).orElse(Duration.ZERO);
    }
}
