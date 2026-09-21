package io.tesseraql.opsui;

import io.tesseraql.core.diag.SqlExecution;
import io.tesseraql.core.diag.SqlExecutionLog;
import io.tesseraql.core.telemetry.SpanSample;
import io.tesseraql.core.telemetry.TraceLog;
import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.Lane;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates operational state for the Operations UI (design ch. 26.11): a batch dashboard and
 * virtual-thread lane diagnostics. The data is read from the job repository and the live execution
 * lanes, so it reflects the running app.
 */
public final class OpsDashboard {

    private static final int SCAN_LIMIT = 200;

    private final JobRepository jobs;
    private final ExecutionLanes lanes;
    private final SqlExecutionLog slowSql;
    private final TraceLog traces;
    private final long slowSpanThresholdMs;
    private final AlertThresholds thresholds;
    private final io.tesseraql.core.diag.PinningMonitor pinning;

    /**
     * The memoized health roll-up (docs/audit-hardening.md Decision 9).
     *
     * <p>{@code health()} walked the whole span ring twice — once directly and once through
     * {@code alerts()} — and probed every datasource, on every poll of an endpoint that is
     * unauthenticated by design. A load balancer polling every second was paying for two full ring
     * scans and a round trip per datasource each time, and anyone who could reach the port could
     * ask for that work as often as they liked.
     *
     * <p>A short TTL rather than a longer one: readiness has to stay readiness. The point is that
     * a burst of polls costs one probe, not that the answer is allowed to go stale.
     */
    private final java.util.concurrent.atomic.AtomicReference<Memoized> cachedHealth = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * One second by default, and the default is the whole trade.
     *
     * <p>A readiness answer may be up to this old, so an orchestrator takes up to this much longer
     * to shed traffic during an outage. One second buys the thing that matters — a burst of polls
     * costs one probe per second no matter how fast it arrives, which is what stops an
     * unauthenticated endpoint from being a lever — while staying inside the interval any
     * orchestrator actually polls at.
     */
    private java.util.function.Supplier<Map<String, String>> routeStatus;

    private volatile java.time.Duration healthTtl = java.time.Duration.ofSeconds(1);

    /** A roll-up and the moment it was computed. */
    private record Memoized(HealthReport report, long computedAtMillis) {
    }

    /** A roll-up already computed, and how long ago (docs/http-threading.md decision 3). */
    public record HeldHealth(HealthReport report, long ageMillis) {
    }
    private java.util.function.Supplier<Map<String, Integer>> outboxCounts;
    private java.util.function.Supplier<Map<String, Integer>> eventCounts;
    private java.util.function.Supplier<Map<String, Boolean>> datasourceProbe;
    private PollSourceStatus pollSources;
    private CalendarStatus calendars;
    /** Per-pool borrow-queue depth, read where the runtime owns the pools (TQL-OPS-9011). */
    private java.util.function.Supplier<Map<String, Map<String, Integer>>> poolStats;
    /** The lifetime admission-refusal count, read from the meter (TQL-OPS-9012). */
    private java.util.function.LongSupplier refusals;
    /**
     * The alert sweep's interval: the window 9011 and 9012 are judged over
     * (docs/deployment-maturity.md decision 9). A pool with borrowers waiting at one sample is a
     * burst; one with borrowers waiting at every sample across a whole interval is the
     * constraint. A refusal is a slowdown; a refusal rate sustained over the interval is the
     * runtime at capacity.
     */
    private volatile long alertIntervalMillis = 60_000L;
    /** The clock the interval rules read; injectable so a test can move it. */
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** When each pool was first seen with borrowers waiting; forgotten when none wait. */
    private final Map<String, Long> awaitingSince = new java.util.concurrent.ConcurrentHashMap<>();
    /** The refusal-rate window: the count and moment it opened, and the rate the last one closed at. */
    private RefusalWindow refusalWindow;
    /** A stop that cut requests at its bound, once one happened (TQL-OPS-9013). */
    private volatile StopCut stopCut;

    private record RefusalWindow(long count, long openedAtMillis, double ratePerSecond) {
    }

    private record StopCut(int requests, java.time.Duration bound) {
    }

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs) {
        this(jobs, lanes, slowSql, traces, slowSpanThresholdMs, AlertThresholds.defaults(), null);
    }

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs, double errorRateWarnPercent) {
        this(jobs, lanes, slowSql, traces, slowSpanThresholdMs,
                new AlertThresholds(errorRateWarnPercent,
                        AlertThresholds.defaults().slowRatePercent(),
                        AlertThresholds.defaults().batchFailureRatePercent()),
                null);
    }

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs, AlertThresholds thresholds) {
        this(jobs, lanes, slowSql, traces, slowSpanThresholdMs, thresholds, null);
    }

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs, AlertThresholds thresholds,
            io.tesseraql.core.diag.PinningMonitor pinning) {
        this.jobs = jobs;
        this.lanes = lanes;
        this.slowSql = slowSql;
        this.traces = traces;
        this.slowSpanThresholdMs = slowSpanThresholdMs;
        this.thresholds = thresholds;
        this.pinning = pinning;
    }

    /**
     * Wires the outbox status counts (roadmap Phase 20), so dead-lettered deliveries raise an
     * operational alert like any other threshold breach.
     */
    public OpsDashboard outboxCounts(java.util.function.Supplier<Map<String, Integer>> counts) {
        this.outboxCounts = counts;
        return this;
    }

    /**
     * Wires the messaging-channel queue status counts (docs/silent-tolerance.md O1), so a
     * dead-lettered queue message raises an operational alert exactly like an outbox one — a
     * consumer that throws on every message must not discard its stream in silence.
     */
    public OpsDashboard eventCounts(java.util.function.Supplier<Map<String, Integer>> counts) {
        this.eventCounts = counts;
        return this;
    }

    /**
     * Wires the datasource probe (roadmap Phase 45): each configured datasource's live validity
     * by name. Readiness degrades to {@code DOWN} when any is false — the truthful state a load
     * balancer needs to shed traffic, where a {@code WARN} never would.
     */
    public OpsDashboard datasourceProbe(java.util.function.Supplier<Map<String, Boolean>> probe) {
        this.datasourceProbe = probe;
        return this;
    }

    /**
     * Wires the poll-source registry (docs/poll-source-status.md), so a source skipped at
     * wire time or failing repeatedly raises an operational alert instead of only a log line.
     */
    public OpsDashboard pollSources(PollSourceStatus pollSources) {
        this.pollSources = pollSources;
        return this;
    }

    /**
     * Wires the business-day calendar registry (docs/jobs.md), so a job whose {@code calendar:}
     * could not be resolved — and which therefore fired unfiltered — raises an operational alert
     * instead of leaving one WARN line as the only trace (docs/silent-tolerance.md O5).
     */
    public OpsDashboard calendars(CalendarStatus calendars) {
        this.calendars = calendars;
        return this;
    }

    /** Wires the pools' borrow-queue depth, so a saturated pool raises {@code TQL-OPS-9011}. */
    public OpsDashboard poolStats(
            java.util.function.Supplier<Map<String, Map<String, Integer>>> stats) {
        this.poolStats = stats;
        return this;
    }

    /** Wires the admission-refusal count, so a sustained refusal rate raises {@code TQL-OPS-9012}. */
    public OpsDashboard refusals(java.util.function.LongSupplier refusals) {
        this.refusals = refusals;
        return this;
    }

    /** The interval 9011 and 9012 are judged over; the runtime binds the alert sweep's period. */
    public OpsDashboard alertInterval(java.time.Duration interval) {
        if (interval != null && !interval.isNegative() && !interval.isZero()) {
            this.alertIntervalMillis = interval.toMillis();
        }
        return this;
    }

    /** The clock the interval rules read (milliseconds); a test moves it instead of sleeping. */
    public OpsDashboard clock(java.util.function.LongSupplier clock) {
        this.clock = clock;
        return this;
    }

    /**
     * Records that a stop reached its drain bound with requests still in flight and cut them
     * ({@code TQL-OPS-9013}): the runtime's own drain or the stack's front. Raised once and kept,
     * because the process is on its way out and the sweep that reads it runs one last time before
     * the pools close.
     */
    public void stopCut(int requests, java.time.Duration bound) {
        this.stopCut = new StopCut(requests, bound);
    }

    /**
     * Wires the route-status contributor (docs/audit-hardening.md Decision 9).
     *
     * <p>A supplier rather than a direct reference because this module has no runtime dependency
     * and a stopped route is the runtime's fact about itself, which nothing here can compute.
     *
     * <p>It contributes a detail and an alert, never the readiness verdict. Gating the verdict on
     * a route-health registry would black out the boot, which is what the engine's own did: its
     * {@code initialState} was DOWN, so a healthy consumer that had not polled yet reported DOWN
     * — on exactly the file and SFTP sources the signal is for.
     */
    public OpsDashboard routeStatus(java.util.function.Supplier<Map<String, String>> routeStatus) {
        this.routeStatus = routeStatus;
        return this;
    }

    /** Builds the dashboard overview: batch summary, lane diagnostics, slow SQL, and recent traces. */
    public Overview overview(int recentLimit) {
        return overview(recentLimit, app -> true);
    }

    /**
     * Builds the overview with the batch executions and traces narrowed to the apps the caller may
     * operate ({@code tql.ops.view.<name>} scope); runtime-wide diagnostics (lanes,
     * slow SQL, pinning, aggregate trace metrics) stay unfiltered behind the entry permission.
     * The traces take the caller's trace scope, which admits an unattributed root the table
     * scope refuses (docs/audit-low-leads.md decision 4).
     */
    public Overview overview(int recentLimit, java.util.function.Predicate<String> appFilter,
            java.util.function.Predicate<String> traceFilter) {
        List<JobExecution> executions = jobs.listExecutions(SCAN_LIMIT).stream()
                .filter(execution -> appFilter.test(execution.appName()))
                .toList();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (JobExecution execution : executions) {
            byStatus.merge(execution.status().name(), 1, Integer::sum);
        }
        List<ExecutionView> recent = executions.stream()
                .limit(Math.max(0, recentLimit))
                .map(OpsDashboard::view)
                .toList();
        List<Alert> alerts = alerts();
        return new Overview(new BatchSummary(executions.size(), byStatus, recent),
                laneStatuses(lanes), slowSql.recent(), traces(traceFilter), traceMetrics(),
                pinning(), !alerts.isEmpty(), alerts);
    }

    /** The overview with one scope for both: the shape before the trace scope was its own. */
    public Overview overview(int recentLimit, java.util.function.Predicate<String> appFilter) {
        return overview(recentLimit, appFilter, appFilter);
    }

    /**
     * The roll-up this dashboard already holds, without computing one.
     *
     * <p>{@code health()} answers the question "what is the state" and pays whatever that costs;
     * this answers "what was the state, and how long ago", which is what a caller that must not
     * block needs (docs/http-threading.md decision 3). Empty until the first roll-up exists, so a
     * caller has to decide what to do before there is anything to serve rather than be handed a
     * default that looks like an answer.
     */
    public java.util.Optional<HeldHealth> heldHealth() {
        Memoized cached = cachedHealth.get();
        return cached == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new HeldHealth(cached.report(),
                        System.currentTimeMillis() - cached.computedAtMillis()));
    }

    /** How long a roll-up is reused before a refresh is due. */
    public java.time.Duration healthTtl() {
        return healthTtl;
    }

    /** How long a health roll-up is reused; the runtime binds the declared key. */
    public OpsDashboard healthTtl(java.time.Duration ttl) {
        if (ttl != null && !ttl.isNegative()) {
            this.healthTtl = ttl;
        }
        return this;
    }

    /**
     * A health roll-up suitable for an actuator/health endpoint (design ch. 19.1, roadmap
     * Phase 45): {@code DOWN} when the datasource probe fails (a dependency the app cannot
     * serve without), {@code WARN} when any alert is active, {@code UP} otherwise — with the
     * key metrics and per-datasource probe results as details.
     */
    public HealthReport health() {
        Memoized cached = cachedHealth.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.computedAtMillis() < healthTtl.toMillis()) {
            return cached.report();
        }
        HealthReport fresh = computeHealth();
        // Last writer wins rather than a lock: two concurrent polls may both compute, which costs
        // one extra probe and never a wrong answer. Serialising them would make the endpoint's
        // latency depend on the slowest datasource for every caller at once.
        cachedHealth.set(new Memoized(fresh, now));
        return fresh;
    }

    private HealthReport computeHealth() {
        TraceMetrics metrics = traceMetrics();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("traceErrorRate", metrics.traceErrorRate());
        details.put("spans", metrics.spans());
        details.put("lanes", lanes == null ? List.of() : laneStatuses(lanes));
        details.put("pinningEvents", pinning().count());
        Map<String, Boolean> datasources = probeDatasources();
        if (!datasources.isEmpty()) {
            details.put("datasources", datasources);
        }
        Map<String, String> stoppedRoutes = routeStatus == null ? Map.of() : routeStatus.get();
        if (!stoppedRoutes.isEmpty()) {
            // A detail and an alert; deliberately not part of the down verdict below.
            details.put("stoppedRoutes", stoppedRoutes);
        }
        boolean down = datasources.containsValue(Boolean.FALSE);
        List<Alert> alerts;
        try {
            // The fresh probe's verdict, not the memo's: a roll-up that read its own predecessor
            // would report DOWN for one TTL after the datasource came back.
            alerts = alerts(down ? failing(datasources) : null);
        } catch (RuntimeException ex) {
            // A contributor that cannot reach its store is itself a DOWN signal: the health
            // endpoint must answer a clean DOWN during an outage, never crash into a 500.
            alerts = List.of();
            details.put("alertsError", String.valueOf(ex.getMessage()));
            down = true;
        }
        details.put("alerts", alerts);
        return new HealthReport(down ? "DOWN" : alerts.isEmpty() ? "UP" : "WARN", details);
    }

    /** The probe results, or an explicit failure entry when probing itself blows up. */
    private Map<String, Boolean> probeDatasources() {
        if (datasourceProbe == null) {
            return Map.of();
        }
        try {
            return datasourceProbe.get();
        } catch (RuntimeException ex) {
            return Map.of("probe", Boolean.FALSE);
        }
    }

    /** The virtual-thread pinning summary (count and recent events), empty when not monitored. */
    public PinningSummary pinning() {
        if (pinning == null) {
            return new PinningSummary(0, List.of());
        }
        return new PinningSummary(pinning.count(), pinning.recent());
    }

    /**
     * Operational alerts derived from the current metrics (design ch. 26.11): a warning is raised
     * when the trace error rate over the retained window reaches the configured threshold.
     */
    public List<Alert> alerts() {
        // What the readiness probe last answered: the same signal an orchestrator sheds on,
        // so a member that has stopped receiving traffic also reaches a human
        // (docs/deployment-maturity.md decision 9).
        String readinessDown = heldHealth()
                .filter(held -> "DOWN".equals(held.report().status()))
                .map(held -> failingFrom(held.report().details()))
                .orElse(null);
        return alerts(readinessDown);
    }

    /** The datasources a probe found down, as the 9010 message names them. */
    private static String failing(Map<String, Boolean> datasources) {
        List<String> names = new java.util.ArrayList<>();
        datasources.forEach((name, valid) -> {
            if (!Boolean.TRUE.equals(valid)) {
                names.add(name);
            }
        });
        return names.isEmpty()
                ? "a contributor failed"
                : "datasource(s) " + String.join(", ", names);
    }

    /** The same, read back from a held report's details. */
    @SuppressWarnings("unchecked")
    private static String failingFrom(Map<String, Object> details) {
        Object datasources = details.get("datasources");
        if (datasources instanceof Map<?, ?> probed) {
            return failing((Map<String, Boolean>) probed);
        }
        return "a contributor failed";
    }

    /**
     * The alerts, given what readiness says: {@code readinessDown} names what is down, or is
     * {@code null} when readiness is not {@code DOWN}.
     */
    private List<Alert> alerts(String readinessDown) {
        TraceMetrics metrics = traceMetrics();
        List<Alert> alerts = new java.util.ArrayList<>();
        if (metrics.traces() > 0 && metrics.traceErrorRate() >= thresholds.errorRatePercent()) {
            alerts.add(new Alert("TQL-OPS-9001", "warning",
                    "Trace error rate " + metrics.traceErrorRate() + "% is at or above the "
                            + thresholds.errorRatePercent() + "% threshold"));
        }
        if (metrics.spans() > 0 && metrics.slowRate() >= thresholds.slowRatePercent()) {
            alerts.add(new Alert("TQL-OPS-9003", "warning",
                    "Slow span rate " + metrics.slowRate() + "% is at or above the "
                            + thresholds.slowRatePercent() + "% threshold"));
        }
        if (lanes != null) {
            for (LaneStatus lane : laneStatuses(lanes)) {
                if (lane.rejected() > 0) {
                    alerts.add(new Alert("TQL-OPS-9002", "warning",
                            "Lane '" + lane.name() + "' rejected " + lane.rejected()
                                    + " request(s) (saturation)"));
                }
            }
        }
        if (pinning != null && pinning.count() > 0) {
            alerts.add(new Alert("TQL-OPS-9005", "warning",
                    pinning.count() + " virtual-thread pinning event(s) detected"));
        }
        if (outboxCounts != null) {
            int dead = outboxCounts.get().getOrDefault("DEAD", 0);
            if (dead > 0) {
                alerts.add(new Alert("TQL-OPS-9006", "warning",
                        dead + " outbox event(s) dead-lettered; inspect the outbox delivery"
                                + " log and redeliver or discard them"));
            }
        }
        if (eventCounts != null) {
            int dead = eventCounts.get().getOrDefault("DEAD", 0);
            if (dead > 0) {
                alerts.add(new Alert("TQL-OPS-9008", "warning",
                        dead + " queue event(s) dead-lettered; inspect the queue events log"
                                + " and redeliver or discard them"));
            }
        }
        if (pollSources != null) {
            for (PollSourceStatus.SourceState source : pollSources.all()) {
                if (source.skipped()) {
                    alerts.add(new Alert("TQL-OPS-9007", "warning",
                            "Poll source for job '" + source.jobId() + "' is not polling: "
                                    + source.reason()));
                } else if (source
                        .consecutiveFailures() >= PollSourceStatus.FAILURE_ALERT_THRESHOLD) {
                    alerts.add(new Alert("TQL-OPS-9007", "warning",
                            "Poll source for job '" + source.jobId() + "' failed "
                                    + source.consecutiveFailures()
                                    + " consecutive import(s); last: " + source.lastResult()));
                }
            }
        }
        if (calendars != null) {
            for (CalendarStatus.FailOpen failOpen : calendars.all()) {
                alerts.add(new Alert("TQL-OPS-9009", "warning",
                        "Job '" + failOpen.jobId() + "' fired unfiltered: its calendar '"
                                + failOpen.calendar() + "' could not be resolved ("
                                + failOpen.reason() + ")"));
            }
        }
        if (jobs != null) {
            List<JobExecution> executions = jobs.listExecutions(SCAN_LIMIT);
            int failed = (int) executions.stream()
                    .filter(e -> e.status() == io.tesseraql.operations.batch.JobStatus.FAILED)
                    .count();
            double failureRate = percent(failed, executions.size());
            if (!executions.isEmpty() && failureRate >= thresholds.batchFailureRatePercent()) {
                alerts.add(new Alert("TQL-OPS-9004", "warning",
                        "Batch failure rate " + failureRate + "% is at or above the "
                                + thresholds.batchFailureRatePercent() + "% threshold"));
            }
        }
        // The four silences of docs/deployment-maturity.md decision 9: a readiness the probe
        // sheds on, a pool that is the constraint, a runtime refusing at capacity, and a stop
        // that cut what it was serving.
        if (readinessDown != null) {
            alerts.add(new Alert("TQL-OPS-9010", "warning",
                    "Readiness is DOWN (" + readinessDown + "); the orchestrator has stopped"
                            + " routing traffic here until it recovers"));
        }
        if (poolStats != null) {
            long now = clock.getAsLong();
            Map<String, Map<String, Integer>> pools = poolStats.get();
            for (Map.Entry<String, Map<String, Integer>> pool : pools.entrySet()) {
                int awaiting = pool.getValue().getOrDefault("awaiting", 0);
                if (awaiting <= 0) {
                    awaitingSince.remove(pool.getKey());
                    continue;
                }
                long since = awaitingSince.computeIfAbsent(pool.getKey(), name -> now);
                if (now - since >= alertIntervalMillis) {
                    alerts.add(new Alert("TQL-OPS-9011", "warning",
                            "Pool '" + pool.getKey() + "' has had " + awaiting
                                    + " thread(s) waiting for a connection at every sample across"
                                    + " the last "
                                    + java.time.Duration.ofMillis(alertIntervalMillis)
                                    + ": the pool, not the database, is the constraint; raise"
                                    + " maximumPoolSize with workerThreads"));
                }
            }
            awaitingSince.keySet().retainAll(pools.keySet());
        }
        if (refusals != null) {
            double rate = refusalRate(clock.getAsLong());
            if (rate >= thresholds.refusalsPerSecond()) {
                alerts.add(new Alert("TQL-OPS-9012", "warning",
                        "Admission refused " + tenths(rate) + " request(s)/s over the last "
                                + java.time.Duration.ofMillis(alertIntervalMillis)
                                + " (threshold " + tenths(thresholds.refusalsPerSecond())
                                + "/s): the runtime is at capacity (TQL-RATE-4293, 4295); raise"
                                + " workerThreads and maxInFlight, or add a replica"));
            }
        }
        StopCut cut = stopCut;
        if (cut != null) {
            alerts.add(new Alert("TQL-OPS-9013", "warning",
                    "A stop reached its drain bound " + cut.bound() + " with " + cut.requests()
                            + " request(s) still in flight and cut them; raise"
                            + " tesseraql.shutdown.timeout and the platform's grace period"
                            + " together"));
        }
        return alerts;
    }

    /** A rate to one decimal place, spelled the same on every locale. */
    private static String tenths(double value) {
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }

    /**
     * The refusal rate over the last closed window: the count grows between windows, a window
     * closes once the interval has elapsed since it opened, and the rate it closed at holds until
     * the next one closes — so the verdict is over an interval, never a sample.
     */
    private synchronized double refusalRate(long now) {
        long count = refusals.getAsLong();
        if (refusalWindow == null) {
            refusalWindow = new RefusalWindow(count, now, 0.0);
            return 0.0;
        }
        long elapsed = now - refusalWindow.openedAtMillis();
        if (elapsed < alertIntervalMillis) {
            return refusalWindow.ratePerSecond();
        }
        double rate = elapsed <= 0 ? 0.0 : (count - refusalWindow.count()) * 1000.0 / elapsed;
        refusalWindow = new RefusalWindow(count, now, rate);
        return rate;
    }

    /**
     * Retention and error-rate metrics over the spans currently held in the trace ring
     * (design ch. 26.11): how many spans/traces are retained and what fraction are errored or slow.
     */
    public TraceMetrics traceMetrics() {
        List<SpanSample> spans = traces.recentSpans();
        int spanCount = spans.size();
        int errorSpans = (int) spans.stream().filter(SpanSample::error).count();
        int slowSpans = (int) spans.stream().filter(s -> s.durationMs() >= slowSpanThresholdMs)
                .count();
        List<TraceSummary> summaries = traceSummaries();
        int traceCount = summaries.size();
        int errorTraces = (int) summaries.stream().filter(s -> s.errorCount() > 0).count();
        return new TraceMetrics(spanCount, errorSpans, percent(errorSpans, spanCount),
                slowSpans, percent(slowSpans, spanCount),
                traceCount, errorTraces, percent(errorTraces, traceCount));
    }

    private static double percent(int part, int total) {
        return total == 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    /** The recent slow SQL executions collected in-process. */
    public List<SqlExecution> slowSql() {
        return slowSql.recent();
    }

    /** The recent spans collected in-process. */
    public List<SpanSample> traces() {
        return traces.recentSpans();
    }

    /**
     * The recent spans narrowed to the caller's trace scope (design ch. 26.11): a span is visible
     * when the root of its retained trace carries an {@code app} attribute the filter accepts.
     * Spans without app attribution (framework-internal work, or traces whose attributed root has
     * been evicted from the ring) reach the filter as {@code null}; {@code OpsScope.traces}
     * admits them for the wildcard reader ({@code tql.ops.view.*}) and hides them from every
     * per-app grant, and the table scope ({@code OpsScope.view}) hides them from everyone — the
     * filter decides, this method does not (docs/audit-low-leads.md decision 4).
     */
    public List<SpanSample> traces(java.util.function.Predicate<String> appFilter) {
        java.util.Set<String> visible = new java.util.HashSet<>();
        for (TraceNode root : traceTree(appFilter)) {
            visible.add(root.span().traceId());
        }
        return traces.recentSpans().stream()
                .filter(span -> visible.contains(span.traceId()))
                .toList();
    }

    /**
     * The recent spans assembled into trace trees by parent/child span ids (design ch. 26.11), with
     * root spans (no parent, or whose parent is no longer retained) at the top.
     */
    public List<TraceNode> traceTree() {
        return traceTree(app -> true);
    }

    /** The trace trees whose root span's {@code app} attribute passes the caller's scope. */
    public List<TraceNode> traceTree(java.util.function.Predicate<String> appFilter) {
        List<SpanSample> spans = traces.recentSpans();
        Map<String, java.util.List<SpanSample>> childrenByParent = new LinkedHashMap<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (SpanSample span : spans) {
            ids.add(span.spanId());
        }
        for (SpanSample span : spans) {
            if (span.parentSpanId() != null && ids.contains(span.parentSpanId())) {
                childrenByParent
                        .computeIfAbsent(span.parentSpanId(), k -> new java.util.ArrayList<>())
                        .add(span);
            }
        }
        List<TraceNode> roots = new java.util.ArrayList<>();
        for (SpanSample span : spans) {
            if ((span.parentSpanId() == null || !ids.contains(span.parentSpanId()))
                    && appFilter.test(app(span))) {
                roots.add(buildNode(span, childrenByParent));
            }
        }
        return roots;
    }

    /** The root span's app attribution, or null for framework-internal (unattributed) spans. */
    private static String app(SpanSample span) {
        Object app = span.attributes().get("app");
        return app == null ? null : String.valueOf(app);
    }

    private TraceNode buildNode(SpanSample span, Map<String, List<SpanSample>> childrenByParent) {
        List<TraceNode> children = childrenByParent.getOrDefault(span.spanId(), List.of()).stream()
                .map(child -> buildNode(child, childrenByParent))
                .toList();
        long childMs = children.stream().mapToLong(TraceNode::durationMs).sum();
        long selfMs = Math.max(0, span.durationMs() - childMs);
        String startedAt = java.time.Instant.ofEpochMilli(span.startedAtEpochMs()).toString();
        return new TraceNode(span, span.durationMs(), selfMs, startedAt,
                span.durationMs() >= slowSpanThresholdMs, children);
    }

    /** Per-trace summaries (total time, slowest span, error/slow counts) for the trace list view. */
    public List<TraceSummary> traceSummaries() {
        return traceSummaries(null);
    }

    /**
     * Per-trace summaries, optionally filtered (design ch. 26.11): {@code "errors"} keeps traces with
     * at least one error span, {@code "slow"} keeps traces with at least one span over the slow
     * threshold; any other value returns all traces.
     */
    public List<TraceSummary> traceSummaries(String filter) {
        return traceSummaries(filter, app -> true);
    }

    /** Per-trace summaries narrowed to the caller's app scope (design ch. 26.11). */
    public List<TraceSummary> traceSummaries(String filter,
            java.util.function.Predicate<String> appFilter) {
        List<TraceSummary> summaries = new java.util.ArrayList<>();
        for (TraceNode root : traceTree(appFilter)) {
            List<TraceNode> all = flatten(root, new java.util.ArrayList<>());
            TraceNode slowest = all.stream()
                    .max(java.util.Comparator.comparingLong(TraceNode::durationMs))
                    .orElse(root);
            int errorCount = (int) all.stream().filter(node -> node.span().error()).count();
            int slowCount = (int) all.stream().filter(TraceNode::slow).count();
            summaries.add(new TraceSummary(root.span().traceId(), root.span().name(),
                    root.durationMs(), all.size(), slowest.span().name(), slowest.durationMs(),
                    errorCount, slowCount));
        }
        if ("errors".equalsIgnoreCase(filter)) {
            return summaries.stream().filter(summary -> summary.errorCount() > 0).toList();
        }
        if ("slow".equalsIgnoreCase(filter)) {
            return summaries.stream().filter(summary -> summary.slowCount() > 0).toList();
        }
        return summaries;
    }

    private static List<TraceNode> flatten(TraceNode node, List<TraceNode> into) {
        into.add(node);
        node.children().forEach(child -> flatten(child, into));
        return into;
    }

    /** Maps each execution lane to its current diagnostics (capacity, in-use, admitted, rejected). */
    public static List<LaneStatus> laneStatuses(ExecutionLanes lanes) {
        return lanes.all().stream().map(OpsDashboard::laneStatus).toList();
    }

    private static LaneStatus laneStatus(Lane lane) {
        int available = lane.available();
        int max = lane.policy().maxConcurrency();
        return new LaneStatus(lane.name(), lane.policy().type().name(), max,
                available, max - available, lane.admittedCount(), lane.rejectedCount());
    }

    private static ExecutionView view(JobExecution execution) {
        String startTime = execution.startTime() == null ? null : execution.startTime().toString();
        return new ExecutionView(execution.id(), execution.jobId(), execution.appName(),
                execution.status().name(), execution.triggerType(), startTime,
                execution.durationMs());
    }

    /** The dashboard overview. */
    public record Overview(BatchSummary batch, List<LaneStatus> lanes, List<SqlExecution> slowSql,
            List<SpanSample> traces, TraceMetrics traceMetrics,
            PinningSummary pinning, boolean warning, List<Alert> alerts) {
    }

    /** Virtual-thread pinning roll-up: total count and the recent pinning events. */
    public record PinningSummary(long count, List<io.tesseraql.core.diag.PinningEvent> recent) {
    }

    /** A health roll-up: a status ({@code UP}/{@code WARN}/{@code DOWN}) and supporting detail metrics. */
    public record HealthReport(String status, Map<String, Object> details) {
    }

    /** An operational alert raised when a metric crosses a threshold. */
    public record Alert(String code, String severity, String message) {
    }

    /**
     * Warning thresholds for the operational alerts (design ch. 26.11): three rates in percent,
     * and the admission-refusal rate in refusals per second over the alert interval
     * (docs/deployment-maturity.md decision 9).
     */
    public record AlertThresholds(double errorRatePercent, double slowRatePercent,
            double batchFailureRatePercent, double refusalsPerSecond) {

        /** The three percent thresholds, with the refusal-rate default of one per second. */
        public AlertThresholds(double errorRatePercent, double slowRatePercent,
                double batchFailureRatePercent) {
            this(errorRatePercent, slowRatePercent, batchFailureRatePercent, 1.0);
        }

        public static AlertThresholds defaults() {
            return new AlertThresholds(5.0, 20.0, 10.0, 1.0);
        }
    }

    /**
     * Retention and error/slow rates over the retained trace ring: counts plus the corresponding
     * percentages (0-100, one decimal place) for spans and traces.
     */
    public record TraceMetrics(int spans, int errorSpans, double spanErrorRate,
            int slowSpans, double slowRate, int traces, int errorTraces, double traceErrorRate) {
    }

    /** Batch execution summary: total scanned, counts by status, and the most recent executions. */
    public record BatchSummary(int total, Map<String, Integer> byStatus,
            List<ExecutionView> recent) {
    }

    /** A compact view of a batch execution for the dashboard ({@code startTime} as ISO-8601). */
    public record ExecutionView(String id, String jobId, String app, String status, String trigger,
            String startTime, Long durationMs) {
    }

    /** Diagnostics for one execution lane (design ch. 24 virtual-thread lanes). */
    public record LaneStatus(String name, String type, int maxConcurrency, int available,
            int inUse, long admitted, long rejected) {
    }

    /**
     * A span and its child spans, formatted for display: {@code durationMs}, {@code selfMs} (time
     * excluding children), an ISO-8601 {@code startedAt}, and a {@code slow} highlight flag.
     */
    public record TraceNode(SpanSample span, long durationMs, long selfMs, String startedAt,
            boolean slow, List<TraceNode> children) {
    }

    /** A roll-up of one trace: total time, span count, slowest span, and error/slow span counts. */
    public record TraceSummary(String traceId, String rootSpan, long totalMs, int spanCount,
            String slowestSpan, long slowestMs, int errorCount, int slowCount) {
    }
}
