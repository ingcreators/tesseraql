package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.rate.RateBudget;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cluster-scoped rate limiter (docs/deployment.md, "Cluster rate limits"): enforcement stays a
 * local token bucket — the fast path never touches the database — but tokens are <em>leased</em>
 * from the shared {@link RateBudget} ledger instead of refilling on the clock, so the declared
 * requests-per-second is one budget across every node sharing the main database. At most one lease
 * attempt runs per second-window per node; claims are first-come-first-served, so a quiet node
 * leaves its share for the busy ones. {@code burst} is node-local smoothing: unclaimed leased
 * tokens accumulate up to the bucket capacity, which is why a grant leased for one window can
 * legitimately be spent in the next.
 *
 * <p><strong>No request thread waits on the ledger.</strong> A claim is a pool borrow plus five
 * statements, and it used to run inside this class's monitor, so every other request to the route
 * queued behind one database call. Those are route virtual threads still holding their runtime
 * admission permit, so one stalled ledger on one route could take the node's whole in-flight budget
 * and answer <em>unrelated</em> routes with the runtime's own at-capacity refusal. The thread that
 * wins a window's lease hands the call to one virtual thread — the shape
 * {@code HealthRoutes.refresh()} already uses in the runtime to recompute behind an answer it has
 * already given — and then goes back round the loop and waits like every other request, for at
 * most {@link #LEASE_WAIT_NANOS}.
 * Exactly one claim is in flight per limiter at a time, whatever the window does, so a stalled
 * ledger costs this route one pooled connection and no admission permit at all.
 *
 * <p>Degrades to availability: when the ledger does not answer, the limiter falls back to the
 * per-node budget for that window (exactly the pre-cluster behavior) and logs with backoff — rate
 * limiting protects resources, it must never become the outage itself. <strong>"Does not answer"
 * is measured against the ledger's own normal latency, not against a constant.</strong> A fixed
 * deadline cannot tell slow from unreachable; it only chooses which latencies get called
 * unreachable, and self-issuing against a ledger that is answering prints tokens no row backs while
 * discarding the row the node just charged. See {@link #staleAfterNanos()}.
 *
 * <p><strong>A window takes one budget</strong> — from the ledger or from the fallback, never both
 * and never twice. Every path that funds a window sets {@link #leasedThisWindow}: the claim start,
 * the stale-claim fallback, and a claim that came back with a self-issued grant.
 *
 * <p><strong>This class assumes {@code budget.claim} returns.</strong> {@link #inFlight} is cleared
 * only when it does, so a claim that never returns leaves this node enforcing its per-node budget
 * for the life of the process without ever asking the ledger again. The bound is
 * {@code JdbcRateLeaseStore}'s query timeout for the statements and the pool's
 * {@code connectionTimeout} for the borrow; a claim out past {@link #STRANDED_NANOS} anyway is
 * reported at {@code ERROR} naming that consequence, because there is no answer this class can give
 * instead.
 */
public final class ClusterRateLimiter {

    private static final TqlErrorCode RATE_LIMIT = new TqlErrorCode(TqlDomain.RATE, 4291);
    private static final Logger LOG = LoggerFactory.getLogger(ClusterRateLimiter.class);

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long LEASE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long DEGRADE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** A claim is presumed dead once it has been out this many times its own normal latency. */
    private static final int SLOW_LEDGER_FACTOR = 4;
    /** ... but never longer than this, so a real outage always degrades. */
    private static final long STALE_CAP_NANOS = 8 * WINDOW_NANOS;
    /** A claim out this long has stranded cluster enforcement; say so at ERROR. */
    private static final long STRANDED_NANOS = 10 * WINDOW_NANOS;

    private static final long NONE = Long.MIN_VALUE;

    private static final class Claim {
        private final long startNanos;
        private final long forWindowSec;
        private final int want;
        private boolean superseded;

        Claim(long startNanos, long forWindowSec, int want) {
            this.startNanos = startNanos;
            this.forWindowSec = forWindowSec;
            this.want = want;
        }
    }

    private final Object lock = new Object();

    private final String scopeKey;
    private final int ratePerSecond;
    private final int capacity;

    private double tokens;
    private long windowSec = NONE;
    private boolean leasedThisWindow;
    private Claim inFlight;
    private int waiting;
    private long lastAnsweredNanos;
    private long lastUnreachableLogNanos;
    private long lastStaleLogNanos;
    private long lastStrandedLogNanos;

    /**
     * A limiter for one cluster-scoped route.
     *
     * @param scopeKey         the budget identity (app + route)
     * @param requestsPerSecond the declared cluster-wide rate
     * @param burst            the bucket capacity, or {@code null} for no node-local smoothing
     */
    public ClusterRateLimiter(String scopeKey, int requestsPerSecond, Integer burst) {
        this.scopeKey = scopeKey;
        this.ratePerSecond = requestsPerSecond;
        this.capacity = Math.max(requestsPerSecond, burst == null ? requestsPerSecond : burst);
        long seed = System.nanoTime() - DEGRADE_LOG_INTERVAL_NANOS;
        this.lastUnreachableLogNanos = seed;
        this.lastStaleLogNanos = seed;
        this.lastStrandedLogNanos = seed;
    }

    /**
     * Returns a processor that rejects with 429 when the leased budget is exhausted.
     *
     * @return the gate step, named so the recipe-governance matrix test can read it back
     */
    public Step acquire() {
        return new Gate();
    }

    final class Gate implements Step {
        @Override
        public void process(Exchange exchange) {
            if (!tryAcquire(exchange)) {
                throw new TqlException(RATE_LIMIT, "Rate limit exceeded");
            }
        }
    }

    private boolean tryAcquire(Exchange exchange) {
        long parkUntilNanos = 0;
        boolean parking = false;
        boolean claimedHere = false;
        while (true) {
            Claim toDispatch = null;
            boolean warnStale = false;
            boolean warnStranded = false;
            boolean decided = false;
            boolean result = false;
            long outSeconds = 0;
            synchronized (lock) {
                long nowMs = System.currentTimeMillis();
                long nowSec = nowMs / 1000;
                long nowNanos = System.nanoTime();
                if (nowSec != windowSec) {
                    windowSec = nowSec;
                    leasedThisWindow = false;
                }
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                Claim claim = inFlight;
                if (claim != null) {
                    long outNanos = nowNanos - claim.startNanos;
                    if (outNanos >= staleAfterNanos()) {
                        if (outNanos >= STRANDED_NANOS
                                && nowNanos - lastStrandedLogNanos >= DEGRADE_LOG_INTERVAL_NANOS) {
                            lastStrandedLogNanos = nowNanos;
                            warnStranded = true;
                        }
                        if (!leasedThisWindow) {
                            warnStale = fallBack(claim, nowNanos);
                        }
                        outSeconds = outNanos / 1_000_000_000L;
                        decided = true;
                        result = spend();
                    } else if (waiting >= claim.want) {
                        decided = true;
                    } else {
                        if (!parking) {
                            parkUntilNanos = nowNanos + LEASE_WAIT_NANOS;
                            parking = true;
                        }
                        parkUntilNanos = Math.min(parkUntilNanos,
                                claim.startNanos + LEASE_WAIT_NANOS);
                        long remainingMs = (parkUntilNanos - nowNanos) / 1_000_000L;
                        if (remainingMs <= 0) {
                            decided = true;
                        } else {
                            waiting++;
                            try {
                                lock.wait(remainingMs);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                decided = true;
                            } finally {
                                waiting--;
                            }
                        }
                    }
                } else if (leasedThisWindow || claimedHere) {
                    decided = true;
                } else {
                    int want = (int) Math.min(ratePerSecond, capacity - tokens);
                    if (want <= 0) {
                        decided = true;
                    } else {
                        leasedThisWindow = true;
                        claimedHere = true;
                        toDispatch = new Claim(nowNanos, nowSec, want);
                        inFlight = toDispatch;
                    }
                }
            }
            if (warnStranded) {
                LOG.error("Cluster rate lease for '{}' has not answered for {}s; this node is"
                        + " enforcing its per-node budget only, and it will not ask the ledger"
                        + " again until that claim ends", scopeKey, outSeconds);
            }
            if (warnStale) {
                LOG.warn("Cluster rate lease for '{}' has not answered in {}s, longer than the"
                        + " window it is leasing for; enforcing the per-node budget",
                        scopeKey, outSeconds);
            }
            if (toDispatch != null) {
                dispatchClaim(exchange, toDispatch);
                continue;
            }
            if (decided) {
                return result;
            }
        }
    }

    /** Caller holds {@link #lock}. */
    private long staleAfterNanos() {
        return Math.max(WINDOW_NANOS,
                Math.min(SLOW_LEDGER_FACTOR * lastAnsweredNanos, STALE_CAP_NANOS));
    }

    /** Caller holds {@link #lock}. */
    private boolean spend() {
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Caller holds {@link #lock}. */
    private boolean fallBack(Claim claim, long nowNanos) {
        leasedThisWindow = true;
        claim.superseded = true;
        int fallback = (int) Math.min(ratePerSecond, capacity - tokens);
        if (fallback > 0) {
            tokens = Math.min(capacity, tokens + fallback);
        }
        lock.notifyAll();
        if (nowNanos - lastStaleLogNanos >= DEGRADE_LOG_INTERVAL_NANOS) {
            lastStaleLogNanos = nowNanos;
            return true;
        }
        return false;
    }

    private void dispatchClaim(Exchange exchange, Claim claim) {
        boolean started = false;
        try {
            RateBudget budget = exchange.beans().lookup(TesseraqlProperties.RATE_BUDGET_BEAN,
                    RateBudget.class);
            if (budget == null) {
                throw new IllegalStateException("rateLimit scope: cluster on '" + scopeKey
                        + "' but no rate-budget ledger is bound");
            }
            Thread.ofVirtual().name("tql-rate-lease-" + scopeKey)
                    .start(() -> runClaim(budget, claim));
            started = true;
        } finally {
            if (!started) {
                finishClaim(claim, 0, null, false);
            }
        }
    }

    private void runClaim(RateBudget budget, Claim claim) {
        int granted = 0;
        Throwable failure = null;
        try {
            granted = budget.claim(scopeKey, claim.forWindowSec, claim.want, ratePerSecond);
        } catch (Throwable ex) {
            granted = claim.want;
            failure = ex;
        } finally {
            finishClaim(claim, granted, failure, failure == null);
        }
    }

    private void finishClaim(Claim claim, int granted, Throwable failure, boolean answered) {
        boolean warnUnreachable;
        synchronized (lock) {
            long nowSec = System.currentTimeMillis() / 1000;
            long nowNanos = System.nanoTime();
            if (nowSec != windowSec) {
                windowSec = nowSec;
                leasedThisWindow = false;
            }
            if (inFlight == claim) {
                inFlight = null;
            }
            if (answered) {
                lastAnsweredNanos = Math.max(0, nowNanos - claim.startNanos);
            }
            if (claim.superseded) {
                granted = 0;
            }
            if (granted > 0) {
                tokens = Math.min(capacity, tokens + granted);
                if (failure != null) {
                    leasedThisWindow = true;
                }
            }
            warnUnreachable = failure != null
                    && nowNanos - lastUnreachableLogNanos >= DEGRADE_LOG_INTERVAL_NANOS;
            if (warnUnreachable) {
                lastUnreachableLogNanos = nowNanos;
            }
            lock.notifyAll();
        }
        if (warnUnreachable) {
            LOG.warn("Cluster rate lease for '{}' unavailable; enforcing per-node budget"
                    + " this window", scopeKey, failure);
        }
    }
}
