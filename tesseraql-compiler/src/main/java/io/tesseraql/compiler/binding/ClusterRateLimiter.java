package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.rate.RateBudget;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cluster-scoped rate limiter (docs/deployment.md, "Cluster rate limits"): enforcement
 * stays a local token bucket — the fast path never touches the database — but tokens are
 * <em>leased</em> from the shared {@link RateBudget} ledger instead of refilling on the clock,
 * so the declared requests-per-second is one budget across every node sharing the main
 * database. At most one lease attempt runs per second-window per node (piggybacked on a
 * request, no background thread to leak across hot reloads); claims are first-come-first-
 * served, so a quiet node leaves its share for the busy ones. {@code burst} is node-local
 * smoothing: unclaimed leased tokens accumulate up to the bucket capacity.
 *
 * <p>Degrades to availability: when the ledger is unreachable the limiter falls back to the
 * per-node budget for that window (exactly the pre-cluster behavior) and logs with backoff —
 * rate limiting protects resources, it must never become the outage itself.
 */
public final class ClusterRateLimiter {

    private static final TqlErrorCode RATE_LIMIT = new TqlErrorCode(TqlDomain.RATE, 4291);
    private static final Logger LOG = LoggerFactory.getLogger(ClusterRateLimiter.class);
    private static final long DEGRADE_LOG_INTERVAL_MS = 60_000;

    private final String scopeKey;
    private final int ratePerSecond;
    private final int capacity;

    private double tokens;
    private long windowSec = Long.MIN_VALUE;
    private boolean leasedThisWindow;
    private long lastDegradeLogMs;

    public ClusterRateLimiter(String scopeKey, int requestsPerSecond, Integer burst) {
        this.scopeKey = scopeKey;
        this.ratePerSecond = requestsPerSecond;
        this.capacity = Math.max(requestsPerSecond,
                burst == null ? requestsPerSecond : burst);
    }

    /** Returns a processor that rejects with 429 when the leased budget is exhausted. */
    public Processor acquire() {
        return exchange -> {
            if (!tryAcquire(exchange)) {
                throw new TqlException(RATE_LIMIT, "Rate limit exceeded");
            }
        };
    }

    private synchronized boolean tryAcquire(Exchange exchange) {
        long nowSec = System.currentTimeMillis() / 1000;
        if (nowSec != windowSec) {
            windowSec = nowSec;
            leasedThisWindow = false;
        }
        // One lease per window per node, taken lazily on the first request that needs it —
        // an idle route costs the ledger nothing.
        if (tokens < 1.0 && !leasedThisWindow) {
            leasedThisWindow = true;
            lease(exchange, nowSec);
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void lease(Exchange exchange, long nowSec) {
        int want = (int) Math.min(ratePerSecond, capacity - tokens);
        if (want <= 0) {
            return;
        }
        RateBudget budget = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.RATE_BUDGET_BEAN, RateBudget.class);
        if (budget == null) {
            throw new IllegalStateException("rateLimit scope: cluster on '" + scopeKey
                    + "' but no rate-budget ledger is bound");
        }
        try {
            tokens = Math.min(capacity, tokens + budget.claim(scopeKey, nowSec, want,
                    ratePerSecond));
        } catch (RuntimeException ex) {
            // Availability over precision: this window falls back to the per-node budget.
            tokens = Math.min(capacity, tokens + want);
            long now = System.currentTimeMillis();
            if (now - lastDegradeLogMs >= DEGRADE_LOG_INTERVAL_MS) {
                lastDegradeLogMs = now;
                LOG.warn("Cluster rate lease for '{}' unavailable; enforcing per-node budget"
                        + " this window: {}", scopeKey, ex.getMessage());
            }
        }
    }
}
