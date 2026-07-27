package com.zenzmoney.core.service.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A Redis-backed fixed-window rate limiter. A policy's windows are checked and
 * consumed atomically by a single Lua script: all windows must admit or none is
 * consumed, so a denial by one window never burns a token in another.
 *
 * <p>Counters live in Redis (surviving app restarts) under keys
 * {@code rl:{<callerKey>}:w<i>}. The {@code rl:} prefix keeps them out of any
 * cache/session keyspace; the {@code {...}} hash tag co-slots a policy's windows
 * for Redis Cluster.
 *
 * <p>Ported from mindmapai's {@code RedisRateLimitService}; runs over Spring
 * Data Redis (Lettuce) instead of raw Jedis.
 */
@Service
public class RedisRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    /**
     * KEYS = one Redis key per window. ARGV = flattened (limit, periodMillis) pairs.
     * Pass 1: check every window; if any would exceed, deny and report the longest
     * remaining TTL among the denying windows — consuming nothing.
     * Pass 2: all admit → INCR each, setting PEXPIRE on first hit.
     * Returns {allowed(0|1), waitMillis}.
     */
    private static final String LUA =
            "local n = #KEYS\n" +
            "local denied = 0\n" +
            "local wait = 0\n" +
            "for i = 1, n do\n" +
            "  local limit = tonumber(ARGV[i*2-1])\n" +
            "  local current = tonumber(redis.call('GET', KEYS[i]) or '0')\n" +
            "  if current + 1 > limit then\n" +
            "    denied = 1\n" +
            "    local ttl = redis.call('PTTL', KEYS[i])\n" +
            "    if ttl > wait then wait = ttl end\n" +
            "  end\n" +
            "end\n" +
            "if denied == 1 then return {0, wait} end\n" +
            "for i = 1, n do\n" +
            "  local period = tonumber(ARGV[i*2])\n" +
            "  local count = redis.call('INCR', KEYS[i])\n" +
            "  if count == 1 then redis.call('PEXPIRE', KEYS[i], period) end\n" +
            "end\n" +
            "return {1, 0}";

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;
    private final StringRedisTemplate redis;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(LUA);
        this.script.setResultType(List.class);
    }

    /**
     * Attempts to consume one token. On a Redis failure this fails <b>open</b>
     * (returns allowed) — use for non-critical paths where availability beats
     * strict throttling.
     */
    public RateLimitResult tryConsume(String callerKey, RateLimitPolicy policy) {
        try {
            return execute(callerKey, policy);
        } catch (Exception e) {
            log.warn("Rate-limit check failed open for key [{}]: {}", callerKey, e.getMessage());
            return RateLimitResult.allow();
        }
    }

    /**
     * Attempts to consume one token. On a Redis failure this fails <b>closed</b>
     * (returns denied, with retry-after = the policy's longest window) — use for
     * abuse-sensitive paths like OTP issuance.
     */
    public RateLimitResult tryConsumeOrDeny(String callerKey, RateLimitPolicy policy) {
        try {
            return execute(callerKey, policy);
        } catch (Exception e) {
            log.error("Rate-limit check failed closed for key [{}]: {}", callerKey, e.getMessage());
            return RateLimitResult.deny(policy.longestPeriod());
        }
    }

    private RateLimitResult execute(String callerKey, RateLimitPolicy policy) {
        List<RateLimitPolicy.Window> windows = policy.windows();
        List<String> keys = new ArrayList<>(windows.size());
        List<String> args = new ArrayList<>(windows.size() * 2);
        for (int i = 0; i < windows.size(); i++) {
            RateLimitPolicy.Window w = windows.get(i);
            keys.add("rl:{" + callerKey + "}:w" + i);
            args.add(Integer.toString(w.limit()));
            args.add(Long.toString(w.period().toMillis()));
        }

        @SuppressWarnings("unchecked")
        List<Object> result = redis.execute(script, keys, (Object[]) args.toArray(new String[0]));
        if (result == null || result.size() < 2) {
            // Unexpected script result — treat as a failure per the caller's policy.
            throw new IllegalStateException("Unexpected rate-limit script result: " + result);
        }

        // Redis returns Lua integers; Spring Data may box them as Long or Integer.
        boolean allowed = asLong(result.get(0)) == 1L;
        if (allowed) {
            return RateLimitResult.allow();
        }
        long waitMillis = asLong(result.get(1));
        return RateLimitResult.deny(Duration.ofMillis(Math.max(0, waitMillis)));
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
