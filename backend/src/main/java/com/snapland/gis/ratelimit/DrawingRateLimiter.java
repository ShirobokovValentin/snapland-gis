package com.snapland.gis.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window per-user drawing action rate limiter.
 *
 * Requests 1..maxActions within the window are counted and allowed.
 * Requests beyond maxActions are rejected without incrementing the counter —
 * the counter stays at maxActions until the window expires.
 * When the window expires the next request starts a fresh window.
 *
 * State is in-memory and is lost on application restart (single-instance
 * prototype limitation; see DESIGN.md for production evolution).
 *
 * Memory is proportional to the number of distinct users who have made
 * mutation attempts. Opportunistic cleanup runs every {@value CLEANUP_INTERVAL}
 * operations to remove entries whose windows have expired, preventing
 * unbounded growth from users who never return. Cleanup uses a weakly-
 * consistent iterator and is approximate; it does not affect correctness.
 */
@Component
public class DrawingRateLimiter {

    static final int CLEANUP_INTERVAL = 128;

    private final DrawingRateLimitProperties props;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, long[]> counters = new ConcurrentHashMap<>();
    private final AtomicInteger operationCount = new AtomicInteger();

    public DrawingRateLimiter(DrawingRateLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * Attempts to consume one quota unit for the given user.
     *
     * @return 0 if the request is allowed; otherwise the number of seconds
     *         (ceiling, minimum 1) until the current fixed window expires.
     */
    public long tryConsume(UUID userId) {
        long now = clock.millis();
        long windowMs = props.window().toMillis();
        long[] retryAfter = {0L};

        counters.compute(userId, (key, current) -> {
            if (current == null || now - current[0] >= windowMs) {
                return new long[]{now, 1L};
            }
            if (current[1] >= props.maxActions()) {
                long remainingMs = current[0] + windowMs - now;
                retryAfter[0] = Math.max(1L, (remainingMs + 999L) / 1000L);
                return current; // do NOT increment the counter
            }
            return new long[]{current[0], current[1] + 1L};
        });

        maybeCleanup(now, windowMs);
        return retryAfter[0];
    }

    /**
     * Clears all per-user counters. Intended for use in tests only.
     */
    public void resetAll() {
        counters.clear();
    }

    private void maybeCleanup(long now, long windowMs) {
        if (operationCount.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            // Weakly-consistent removal: entries updated by a concurrent compute()
            // between iterator read and removal are preserved by removeIf's
            // conditional-remove semantics.
            counters.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMs);
        }
    }
}
