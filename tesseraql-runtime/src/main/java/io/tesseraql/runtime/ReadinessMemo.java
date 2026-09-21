package io.tesseraql.runtime;

import io.tesseraql.opsui.OpsDashboard;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The readiness answer, memoised, and the rule for when it stops being one
 * (docs/http-threading.md decision 3; the rule corrected by docs/deployment-maturity.md
 * decision 3).
 *
 * <p>The roll-up {@link OpsDashboard} holds is served from memory and refreshed behind the
 * answer, so a poll costs the event loop a map read and never a probe. Staleness used to count
 * from the memo's last <em>completion</em>: three TTLs without one answered {@code DOWN}.
 * Measured, that read a prober's own silence as a refresh that failed — nothing refreshes the
 * memo but a poll, so a probe every five or ten seconds (Kubernetes' default period is ten) was
 * answered {@code DOWN} on every probe by a runtime that was entirely well, and its pod never
 * joined the Service. Staleness now counts from the last refresh <em>attempt</em>: a poll after a
 * quiet spell answers the held status and starts a refresh, and {@code DOWN} is the answer only
 * when a refresh that started {@link #STALE_AFTER_TTLS} TTLs ago has not landed — the hung
 * database the rule was written for, which holds the probe for {@code connectionTimeout}.
 *
 * <p>One memo per runtime: the member's own {@code /_tesseraql/health/ready} reads it, and so
 * does the origin's roll-up over every hosted runtime ({@link StackReadiness}), so both paths
 * answer from the same state and both keep it fresh.
 */
final class ReadinessMemo {

    /** What the memo reads and refreshes: the dashboard in production, a fake in tests. */
    interface Source {

        /** The roll-up already held, with its age; empty until the first one exists. */
        Optional<OpsDashboard.HeldHealth> held();

        /** Computes a fresh roll-up, blocking for as long as a datasource probe takes. */
        String compute();
    }

    /**
     * How many TTLs a refresh may be in flight before its absence is the answer.
     *
     * <p>One would flap: a refresh legitimately takes as long as the probe it performs. Three
     * means the probe has hung past two further polls, which is not a hiccup. An operator whose
     * probes are legitimately slower than that raises {@code tesseraql.diagnostics.readinessTtl},
     * the number that already governs how fresh readiness is.
     */
    static final int STALE_AFTER_TTLS = 3;

    private final Source source;
    private final long ttlMillis;
    private final LongSupplier clock;
    /** At most one refresh at a time: a burst of polls must not become a burst of probes. */
    private final AtomicBoolean refreshing = new AtomicBoolean();
    /** When the refresh in flight started, on {@link #clock}; meaningful while refreshing. */
    private volatile long attemptStartedMillis;

    ReadinessMemo(Source source, long ttlMillis, LongSupplier clock) {
        this.source = source;
        this.ttlMillis = Math.max(1, ttlMillis);
        this.clock = clock;
    }

    /** The production shape: the dashboard's own memo, its declared TTL, the wall clock. */
    static ReadinessMemo over(OpsDashboard dashboard) {
        return new ReadinessMemo(new Source() {
            @Override
            public Optional<OpsDashboard.HeldHealth> held() {
                return dashboard.heldHealth();
            }

            @Override
            public String compute() {
                return dashboard.health().status();
            }
        }, dashboard.healthTtl().toMillis(), System::currentTimeMillis);
    }

    /** The roll-up already held, or empty once per process before the first exists. */
    Optional<OpsDashboard.HeldHealth> held() {
        return source.held();
    }

    /**
     * The status to answer for a held roll-up: its own status, with a refresh started behind the
     * answer when it is due, or {@code DOWN} when the refresh in flight has outlived its bound.
     */
    String status(OpsDashboard.HeldHealth held) {
        if (held.ageMillis() >= ttlMillis) {
            refresh();
        }
        if (refreshing.get()
                && clock.getAsLong() - attemptStartedMillis >= ttlMillis * STALE_AFTER_TTLS) {
            return "DOWN";
        }
        return held.report().status();
    }

    /**
     * The status to answer without waiting: what {@link #status} says, or empty when no roll-up
     * exists yet — in which case the first one is started so the next poll has an answer. The
     * origin's roll-up reads every member this way; it must never block the gateway's loop.
     */
    Optional<String> poll() {
        Optional<OpsDashboard.HeldHealth> held = source.held();
        if (held.isEmpty()) {
            refresh();
            return Optional.empty();
        }
        return Optional.of(status(held.get()));
    }

    /** A roll-up computed now, or {@code DOWN} when computing it threw — never an exception. */
    String rollUp() {
        try {
            return source.compute();
        } catch (RuntimeException unavailable) {
            return "DOWN";
        }
    }

    /** Whether a refresh is in flight; for the tests that pin the bound. */
    boolean refreshing() {
        return refreshing.get();
    }

    /**
     * Recomputes behind the answer already given.
     *
     * <p>The poll that finds the memo due does not wait for the new one: it is answered from what
     * is held, and the next poll gets the fresher result. The attempt's start is recorded before
     * the thread runs, so the staleness rule above measures the refresh, not the thread scheduler.
     */
    private void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        attemptStartedMillis = clock.getAsLong();
        Thread.ofVirtual().name("tql-readiness-refresh").start(() -> {
            try {
                rollUp();
            } finally {
                refreshing.set(false);
            }
        });
    }
}
