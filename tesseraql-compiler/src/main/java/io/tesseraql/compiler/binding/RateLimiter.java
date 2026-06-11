package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import org.apache.camel.Processor;

/**
 * Token-bucket rate limiter for a route (design ch. 36.1). Requests beyond the sustained
 * {@code requestsPerSecond} (allowing short bursts up to the bucket capacity) are rejected with
 * {@code TQL-RATE-4291} (429).
 */
public final class RateLimiter {

    private static final TqlErrorCode RATE_LIMIT = new TqlErrorCode(TqlDomain.RATE, 4291);

    private final double ratePerSecond;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    public RateLimiter(double requestsPerSecond, double burst) {
        this.ratePerSecond = requestsPerSecond;
        this.capacity = Math.max(1, burst);
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Returns a processor that rejects when no token is available. */
    public Processor acquire() {
        return exchange -> {
            if (!tryAcquire()) {
                throw new TqlException(RATE_LIMIT, "Rate limit exceeded");
            }
        };
    }

    private synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
