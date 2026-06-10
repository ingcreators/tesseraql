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

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs) {
        this(jobs, lanes, slowSql, traces, slowSpanThresholdMs, AlertThresholds.defaults(), null);
    }

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs, double errorRateWarnPercent) {
        this(jobs, lanes, slowSql, traces, slowSpanThresholdMs,
                new AlertThresholds(errorRateWarnPercent, AlertThresholds.defaults().slowRatePercent(),
                        AlertThresholds.defaults().batchFailureRatePercent()), null);
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

    /** Builds the dashboard overview: batch summary, lane diagnostics, slow SQL, and recent traces. */
    public Overview overview(int recentLimit) {
        return overview(recentLimit, app -> true);
    }

    /**
     * Builds the overview with the batch executions narrowed to the apps the caller may operate
     * (design ch. 26.11 {@code ops.app.<name>} scope); runtime-wide diagnostics (lanes, slow SQL,
     * traces, pinning) stay unfiltered behind the entry permission.
     */
    public Overview overview(int recentLimit, java.util.function.Predicate<String> appFilter) {
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
                laneStatuses(lanes), slowSql.recent(), traces.recentSpans(), traceMetrics(),
                pinning(), !alerts.isEmpty(), alerts);
    }

    /**
     * A health roll-up suitable for an actuator/health endpoint (design ch. 19.1): {@code UP} when
     * there are no active alerts, {@code WARN} otherwise, with the key metrics as details.
     */
    public HealthReport health() {
        List<Alert> alerts = alerts();
        TraceMetrics metrics = traceMetrics();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("traceErrorRate", metrics.traceErrorRate());
        details.put("spans", metrics.spans());
        details.put("lanes", lanes == null ? List.of() : laneStatuses(lanes));
        details.put("pinningEvents", pinning().count());
        details.put("alerts", alerts);
        return new HealthReport(alerts.isEmpty() ? "UP" : "WARN", details);
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
        if (jobs != null) {
            List<JobExecution> executions = jobs.listExecutions(SCAN_LIMIT);
            int failed = (int) executions.stream()
                    .filter(e -> e.status() == io.tesseraql.operations.batch.JobStatus.FAILED).count();
            double failureRate = percent(failed, executions.size());
            if (!executions.isEmpty() && failureRate >= thresholds.batchFailureRatePercent()) {
                alerts.add(new Alert("TQL-OPS-9004", "warning",
                        "Batch failure rate " + failureRate + "% is at or above the "
                                + thresholds.batchFailureRatePercent() + "% threshold"));
            }
        }
        return alerts;
    }

    /**
     * Retention and error-rate metrics over the spans currently held in the trace ring
     * (design ch. 26.11): how many spans/traces are retained and what fraction are errored or slow.
     */
    public TraceMetrics traceMetrics() {
        List<SpanSample> spans = traces.recentSpans();
        int spanCount = spans.size();
        int errorSpans = (int) spans.stream().filter(SpanSample::error).count();
        int slowSpans = (int) spans.stream().filter(s -> s.durationMs() >= slowSpanThresholdMs).count();
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
     * The recent spans assembled into trace trees by parent/child span ids (design ch. 26.11), with
     * root spans (no parent, or whose parent is no longer retained) at the top.
     */
    public List<TraceNode> traceTree() {
        List<SpanSample> spans = traces.recentSpans();
        Map<String, java.util.List<SpanSample>> childrenByParent = new LinkedHashMap<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (SpanSample span : spans) {
            ids.add(span.spanId());
        }
        for (SpanSample span : spans) {
            if (span.parentSpanId() != null && ids.contains(span.parentSpanId())) {
                childrenByParent.computeIfAbsent(span.parentSpanId(), k -> new java.util.ArrayList<>())
                        .add(span);
            }
        }
        List<TraceNode> roots = new java.util.ArrayList<>();
        for (SpanSample span : spans) {
            if (span.parentSpanId() == null || !ids.contains(span.parentSpanId())) {
                roots.add(buildNode(span, childrenByParent));
            }
        }
        return roots;
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
        List<TraceSummary> summaries = new java.util.ArrayList<>();
        for (TraceNode root : traceTree()) {
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

    /** A health roll-up: a status ({@code UP}/{@code WARN}) and supporting detail metrics. */
    public record HealthReport(String status, Map<String, Object> details) {
    }

    /** An operational alert raised when a metric crosses a threshold. */
    public record Alert(String code, String severity, String message) {
    }

    /** Warning thresholds (percent) for the operational alerts (design ch. 26.11). */
    public record AlertThresholds(double errorRatePercent, double slowRatePercent,
            double batchFailureRatePercent) {

        public static AlertThresholds defaults() {
            return new AlertThresholds(5.0, 20.0, 10.0);
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
    public record BatchSummary(int total, Map<String, Integer> byStatus, List<ExecutionView> recent) {
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
