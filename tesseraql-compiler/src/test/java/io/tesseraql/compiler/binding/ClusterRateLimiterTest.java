package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.rate.RateBudget;
import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cluster limiter's claim leaves the monitor, and a window still takes one budget
 * (docs/audit-medium-leads.md, F119).
 *
 * <p><strong>Not one assertion here is "requests are served."</strong> A served-count assertion is
 * green on the unfixed code whenever the ledger is healthy, and green again on a limiter that
 * self-issues every token it hands out — which is the failure this change could introduce. Every
 * assertion below is on a ledger that is provably still blocked at the moment it runs, on what the
 * ledger was handed or answered, on a latency, or on the log.
 *
 * <p>Three of these — {@link #aNodeServesOnlyWhatTheLedgerGranted},
 * {@link #aSlowButAnsweringLedgerIsNotSelfIssuedAgainst} and
 * {@link #aClaimThatCouldNotBeDispatchedLeavesTheLimiterAbleToLeaseAgain} — pass on the code as it
 * stood before F119, and that is deliberate. They pin what this change must not break. A suite that
 * is uniformly red on the old file measures "is this the old file", not "is this correct".
 *
 * <p>Two of them assert a wall-clock precondition rather than hoping for it: a boundary-aligned
 * driver that slips into the next window reports {@code slipped windows} and fails legibly instead
 * of passing vacuously. On a heavily loaded runner that is a flake surface, and it is the right
 * trade — the naive form of {@link #aWindowFundedByTheFallbackDoesNotAlsoBuyALease} passes on a
 * broken build.
 */
class ClusterRateLimiterTest {

    /** Everything the limiter logs lands here; slf4j-simple writes to {@code System.err}. */
    private static final ByteArrayOutputStream LOG_CAPTURE = new ByteArrayOutputStream();

    private static PrintStream realErr;

    private static final String SCOPE = "g|route";

    @BeforeAll
    static void captureTheLog() {
        realErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                LOG_CAPTURE.write(b);
                realErr.write(b);
            }
        }, true));
    }

    @AfterAll
    static void releaseTheLog() {
        System.setErr(realErr);
    }

    @BeforeEach
    void freshLog() {
        LOG_CAPTURE.reset();
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static Beans beansFor(RateBudget ledger) {
        return new Beans() {
            @Override
            public <T> T lookup(String name, Class<T> type) {
                return TesseraqlProperties.RATE_BUDGET_BEAN.equals(name) && ledger != null
                        ? type.cast(ledger)
                        : null;
            }
        };
    }

    private static Step limiter(int rate) {
        return new ClusterRateLimiter(SCOPE, rate, null).acquire();
    }

    /** One request through the gate: true when it was served, false when it was refused. */
    private static boolean served(Step gate, Beans beans) {
        try {
            gate.process(new Exchange(beans));
            return true;
        } catch (Exception | Error refused) {
            return false;
        }
    }

    /** Parks until {@code msBefore} milliseconds before the next second, then to the boundary. */
    private static void alignToSecond(long msBefore) throws InterruptedException {
        long now;
        do {
            now = System.currentTimeMillis();
        } while (1000 - now % 1000 > msBefore);
        Thread.sleep(1000 - now % 1000);
    }

    private static long windowNow() {
        return System.currentTimeMillis() / 1000;
    }

    /** A ledger that blocks every claim until the test releases it. */
    private static final class Latched implements RateBudget {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Long> windows = Collections.synchronizedList(new ArrayList<>());
        private final int grant;

        Latched(int grant) {
            this.grant = grant;
        }

        @Override
        public int claim(String scopeKey, long window, int want, int budget) {
            calls.incrementAndGet();
            windows.add(window);
            try {
                gate.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Math.min(want, grant);
        }

        long callsFor(long window) {
            synchronized (windows) {
                return windows.stream().filter(seen -> seen == window).count();
            }
        }
    }

    /** A ledger that answers correctly, after a latency, with real per-window arithmetic. */
    private static final class Budgeted implements RateBudget {
        private final Map<Long, Integer> granted = new HashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger authorised = new AtomicInteger();
        private final long latencyMs;
        private final int perWindow;

        Budgeted(long latencyMs, int perWindow) {
            this.latencyMs = latencyMs;
            this.perWindow = perWindow;
        }

        @Override
        public int claim(String scopeKey, long window, int want, int budget) {
            calls.incrementAndGet();
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
            synchronized (granted) {
                int seen = granted.getOrDefault(window, 0);
                int grant = Math.min(want, perWindow - seen);
                if (grant <= 0) {
                    return 0;
                }
                granted.put(window, seen + grant);
                authorised.addAndGet(grant);
                return grant;
            }
        }
    }

    /** A ledger that fails, immediately, with whatever the arm hands it. */
    private static final class Thrower implements RateBudget {
        private final Throwable failure;

        Thrower(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public int claim(String scopeKey, long window, int want, int budget) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) failure;
        }
    }

    /** A ledger that fails, but only after a latency, so its credit lands in a later window. */
    private static final class FailsAfter implements RateBudget {
        private final long latencyMs;
        private final List<Long> windows = Collections.synchronizedList(new ArrayList<>());

        FailsAfter(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        @Override
        public int claim(String scopeKey, long window, int want, int budget) {
            windows.add(window);
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("rate-lease claim failed");
        }
    }

    // ---- G1 -------------------------------------------------------------------------------

    /**
     * The defect itself. The ledger is still blocked when the assertion runs — the latch is
     * released afterwards — so this cannot pass by the claim quietly completing.
     */
    @Test
    void everyoneAnswersWhileAClaimIsHeldAtTheLedger() throws Exception {
        Latched ledger = new Latched(10);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        AtomicLong worstMs = new AtomicLong();
        AtomicInteger answered = new AtomicInteger();
        List<Thread> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int index = i;
            requests.add(Thread.ofVirtual().start(() -> {
                if (index > 0) {
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                long start = System.nanoTime();
                served(gate, beans);
                worstMs.updateAndGet(m -> Math.max(m, (System.nanoTime() - start) / 1_000_000));
                answered.incrementAndGet();
            }));
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (answered.get() < 8 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        try {
            assertThat(answered.get())
                    .as("every request answers while the ledger still holds the claim")
                    .isEqualTo(8);
            assertThat(worstMs.get())
                    .as("no request waits on the ledger")
                    .isLessThan(400);
        } finally {
            ledger.gate.countDown();
            for (Thread request : requests) {
                request.join(500);
            }
        }
    }

    // ---- G2 -------------------------------------------------------------------------------

    /** Green before this change, and it must stay green: the node obeys a partial grant. */
    @Test
    void aNodeServesOnlyWhatTheLedgerGranted() throws Exception {
        Budgeted ledger = new Budgeted(5, 3);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        alignToSecond(500);
        int served = 0;
        long stop = System.currentTimeMillis() + 700;
        while (System.currentTimeMillis() < stop) {
            if (served(gate, beans)) {
                served++;
            } else {
                Thread.sleep(5);
            }
        }
        assertThat(served)
                .as("the ledger authorised 3 of a rate-10 ask, so the node serves 3")
                .isEqualTo(3);
        assertThat(ledger.authorised.get()).isEqualTo(3);
    }

    // ---- G3 -------------------------------------------------------------------------------

    /**
     * The degrade still happens, and it happens promptly. The batch latency is the assertion that
     * separates this from the old code, which serves the same count by queueing behind the claim.
     */
    @Test
    void aStalledLedgerStillServesThePerNodeBudgetPromptly() throws Exception {
        Latched ledger = new Latched(10);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        alignToSecond(800);
        served(gate, beans);
        Thread.sleep(1200);
        long start = System.nanoTime();
        int served = 0;
        for (int i = 0; i < 12; i++) {
            if (served(gate, beans)) {
                served++;
            }
        }
        long batchMs = (System.nanoTime() - start) / 1_000_000;
        try {
            assertThat(served).as("the per-node budget is served").isGreaterThanOrEqualTo(10);
            assertThat(batchMs).as("and it is served without waiting on the ledger")
                    .isLessThan(1000);
        } finally {
            ledger.gate.countDown();
            Thread.sleep(100);
        }
    }

    // ---- G4 -------------------------------------------------------------------------------

    /**
     * A window takes one budget. The stale claim is released deliberately inside the degraded
     * window, so a limiter that lets both the fallback and the late claim pay is caught here.
     */
    @Test
    void aWindowFundedByTheFallbackDoesNotAlsoBuyALease() throws Exception {
        Latched ledger = new Latched(10);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        alignToSecond(800);
        served(gate, beans);
        Thread.sleep(1150);
        long degraded = windowNow();
        int drained = 0;
        for (int i = 0; i < 14; i++) {
            if (served(gate, beans)) {
                drained++;
            }
        }
        ledger.gate.countDown();
        Thread.sleep(120);
        int after = 0;
        for (int i = 0; i < 14; i++) {
            if (served(gate, beans)) {
                after++;
            }
        }
        assertThat(windowNow())
                .as("precondition: the whole exercise stays inside the degraded window;"
                        + " slipped windows means this ran on a loaded machine, not that the"
                        + " limiter is wrong")
                .isEqualTo(degraded);
        assertThat(drained + after)
                .as("the degraded window is funded once, by the fallback")
                .isLessThanOrEqualTo(10);
        assertThat(ledger.callsFor(degraded))
                .as("and it never also buys a lease for that window")
                .isZero();
    }

    // ---- G5 -------------------------------------------------------------------------------

    /**
     * The repair the three candidate designs did not have. A constant deadline cannot tell a slow
     * ledger from an unreachable one: at a 1200 ms claim latency every one of them self-issued
     * against a ledger that was answering correctly, and discarded the grants it did return.
     */
    @Test
    void aSlowButAnsweringLedgerIsNotSelfIssuedAgainst() throws Exception {
        Budgeted ledger = new Budgeted(1200, 4);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        AtomicInteger served = new AtomicInteger();
        long end = System.currentTimeMillis() + 9000;
        while (System.currentTimeMillis() < end) {
            Thread.ofVirtual().start(() -> {
                if (served(gate, beans)) {
                    served.incrementAndGet();
                }
            });
            Thread.sleep(40);
        }
        Thread.sleep(300);
        assertThat(served.get())
                .as("what the node served is what the ledger authorised, give or take one"
                        + " bucket — read this number, not the throughput")
                .isLessThanOrEqualTo(ledger.authorised.get() + 10);
    }

    // ---- G6 -------------------------------------------------------------------------------

    /**
     * The one assertion covering a permanent, silent loss of the whole point of the class: a claim
     * that never returns leaves the in-flight mark set for the life of the process, so the ledger
     * is never asked again even after the database recovers. There is no answer the limiter can
     * give instead, so it says so in the log.
     *
     * <p>The slowest assertion in the suite at about twelve seconds — it has to drive past the
     * stranded threshold. If CI ever shows a lone failure here, lower that threshold or move this
     * case to the integration test. Do not delete it.
     */
    @Test
    void aLedgerThatNeverAnswersIsReportedAtError() throws Exception {
        Latched ledger = new Latched(10);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        served(gate, beans);
        long end = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < end) {
            served(gate, beans);
            Thread.sleep(30);
        }
        System.err.flush();
        String log = LOG_CAPTURE.toString();
        try {
            assertThat(log)
                    .as("the stranded claim is reported at ERROR, naming the scope it stranded")
                    .contains("ERROR")
                    .contains(SCOPE);
        } finally {
            ledger.gate.countDown();
        }
    }

    // ---- G7 -------------------------------------------------------------------------------

    /**
     * The degrade logs the throwable, not {@code getMessage()} — an Error's message is usually
     * null, so the old form logged nothing usable. This is F106's ClusterRateLimiter half, landed
     * here because this change rewrites both log lines anyway.
     */
    @Test
    void anErrorFromTheLedgerDegradesAndTheThrowableIsLogged() throws Exception {
        assertDegradesAndLogs(new NoClassDefFoundError("org/postgresql/Driver"));
    }

    /** The falsifier arm: the same assertion on the failure shape that always did degrade. */
    @Test
    void aRuntimeExceptionFromTheLedgerDegradesAndTheThrowableIsLogged() throws Exception {
        assertDegradesAndLogs(new IllegalStateException("rate-lease claim failed"));
    }

    private void assertDegradesAndLogs(Throwable failure) throws Exception {
        Beans beans = beansFor(new Thrower(failure));
        Step gate = limiter(10);
        int served = 0;
        long end = System.currentTimeMillis() + 2200;
        while (System.currentTimeMillis() < end) {
            if (served(gate, beans)) {
                served++;
            }
            Thread.sleep(10);
        }
        System.err.flush();
        String log = LOG_CAPTURE.toString();
        assertThat(served)
                .as("the ledger failing is not the outage: the per-node budget still serves")
                .isGreaterThanOrEqualTo(10);
        assertThat(log).as("the degrade is reported").contains("unavailable");
        assertThat(log)
                .as("and the throwable reaches the log, not just its usually-null message")
                .contains(failure.getClass().getName());
    }

    // ---- G8 -------------------------------------------------------------------------------

    /**
     * Green before this change, and it must stay green. A misconfiguration — no ledger bound —
     * propagates, and does not leave the limiter believing a claim is in flight for ever. The last
     * assertion is the load-bearing one: after a ledger appears, it is actually called.
     */
    @Test
    void aClaimThatCouldNotBeDispatchedLeavesTheLimiterAbleToLeaseAgain() throws Exception {
        Beans nothingBound = beansFor(null);
        Step gate = limiter(10);
        boolean propagated = false;
        try {
            gate.process(new Exchange(nothingBound));
        } catch (IllegalStateException expected) {
            propagated = true;
        } catch (Exception | Error other) {
            propagated = false;
        }
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            served(gate, nothingBound);
        }
        long laterMs = (System.nanoTime() - start) / 1_000_000;
        Budgeted ledger = new Budgeted(5, 10);
        Beans beans = beansFor(ledger);
        Thread.sleep(1050);
        for (int i = 0; i < 3; i++) {
            served(gate, beans);
        }
        Thread.sleep(200);
        assertThat(propagated).as("the misconfiguration is not swallowed").isTrue();
        assertThat(laterMs).as("and later requests are not parked behind a claim that never was")
                .isLessThan(60);
        assertThat(ledger.calls.get())
                .as("once a ledger is bound the limiter leases again")
                .isGreaterThanOrEqualTo(1);
    }

    // ---- G9 -------------------------------------------------------------------------------

    /**
     * Structural, because the defect is a placement and six of the nine reviews of the candidate
     * designs found it: every design hoisted the clock read out of the lock, and the behavioural
     * assertions above stayed green on all of them. A source walk is the only cheap thing that
     * catches it.
     *
     * <p><strong>What this does not catch</strong>, said here rather than assumed: a clock read
     * moved into a helper that is called from inside the lock, and an unbalanced brace inside a
     * string literal, which would defeat the depth count.
     */
    @Test
    void theClockThatDecidesTheWindowIsReadUnderTheLock() throws Exception {
        Path source = Path
                .of("src/main/java/io/tesseraql/compiler/binding/ClusterRateLimiter.java");
        assertThat(source).as("the walk needs the source it walks").exists();
        String text = Files.readString(source);
        int depth = 0;
        int syncDepth = -1;
        int outside = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.startsWith("synchronized (lock) {", i)) {
                syncDepth = syncDepth < 0 ? depth : syncDepth;
            }
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (syncDepth >= 0 && depth <= syncDepth) {
                    syncDepth = -1;
                }
            } else if (text.startsWith("System.currentTimeMillis()", i) && syncDepth < 0) {
                outside++;
            }
        }
        assertThat(outside)
                .as("every clock read that decides a window sits inside synchronized (lock)")
                .isZero();
    }

    // ---- G10 ------------------------------------------------------------------------------

    /**
     * The assertion the first ten did not have. A claim that fails <em>inside</em> the staleness
     * deadline is never superseded, so its self-issued credit lands in a window the rollover has
     * just cleared — and the next request to drain those tokens arms a second, ledger-funded
     * budget for the same window. {@link #aWindowFundedByTheFallbackDoesNotAlsoBuyALease} cannot
     * see it: its ledger is latched, so its claim is superseded and the marking branch is never
     * reached.
     */
    @Test
    void aSelfIssuedCreditRecordsTheWindowItLandsIn() throws Exception {
        FailsAfter ledger = new FailsAfter(300);
        Beans beans = beansFor(ledger);
        Step gate = limiter(10);
        alignToSecond(800);
        Thread.sleep(800);
        long armedIn = windowNow();
        served(gate, beans);
        Thread.sleep(400);
        long landedIn = windowNow();
        int served = 0;
        for (int i = 0; i < 26; i++) {
            if (served(gate, beans)) {
                served++;
            }
        }
        Thread.sleep(350);
        for (int i = 0; i < 14; i++) {
            if (served(gate, beans)) {
                served++;
            }
        }
        long callsForLanded;
        synchronized (ledger.windows) {
            callsForLanded = ledger.windows.stream().filter(seen -> seen == landedIn).count();
        }
        assertThat(landedIn)
                .as("precondition: the credit lands in the window after the one it was armed for")
                .isEqualTo(armedIn + 1);
        assertThat(windowNow())
                .as("precondition: the drain stays inside that window; slipped windows means a"
                        + " loaded machine, not a wrong limiter")
                .isEqualTo(landedIn);
        assertThat(served)
                .as("the window the self-issued credit landed in is funded once")
                .isLessThanOrEqualTo(10);
        assertThat(callsForLanded)
                .as("and it never also buys a lease for that window")
                .isZero();
    }
}
