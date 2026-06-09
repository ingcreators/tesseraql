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

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql,
            TraceLog traces, long slowSpanThresholdMs) {
        this.jobs = jobs;
        this.lanes = lanes;
        this.slowSql = slowSql;
        this.traces = traces;
        this.slowSpanThresholdMs = slowSpanThresholdMs;
    }

    /** Builds the dashboard overview: batch summary, lane diagnostics, slow SQL, and recent traces. */
    public Overview overview(int recentLimit) {
        List<JobExecution> executions = jobs.listExecutions(SCAN_LIMIT);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (JobExecution execution : executions) {
            byStatus.merge(execution.status().name(), 1, Integer::sum);
        }
        List<ExecutionView> recent = executions.stream()
                .limit(Math.max(0, recentLimit))
                .map(OpsDashboard::view)
                .toList();
        return new Overview(new BatchSummary(executions.size(), byStatus, recent),
                laneStatuses(lanes), slowSql.recent(), traces.recentSpans());
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

    /** A per-trace summary (total time and slowest span) for the trace list view (design ch. 26.11). */
    public List<TraceSummary> traceSummaries() {
        List<TraceSummary> summaries = new java.util.ArrayList<>();
        for (TraceNode root : traceTree()) {
            List<TraceNode> all = flatten(root, new java.util.ArrayList<>());
            TraceNode slowest = all.stream()
                    .max(java.util.Comparator.comparingLong(TraceNode::durationMs))
                    .orElse(root);
            summaries.add(new TraceSummary(root.span().traceId(), root.span().name(),
                    root.durationMs(), all.size(), slowest.span().name(), slowest.durationMs()));
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
        return new ExecutionView(execution.id(), execution.jobId(), execution.status().name(),
                execution.triggerType(), startTime, execution.durationMs());
    }

    /** The dashboard overview. */
    public record Overview(BatchSummary batch, List<LaneStatus> lanes, List<SqlExecution> slowSql,
            List<SpanSample> traces) {
    }

    /** Batch execution summary: total scanned, counts by status, and the most recent executions. */
    public record BatchSummary(int total, Map<String, Integer> byStatus, List<ExecutionView> recent) {
    }

    /** A compact view of a batch execution for the dashboard ({@code startTime} as ISO-8601). */
    public record ExecutionView(String id, String jobId, String status, String trigger,
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

    /** A roll-up of one trace: its total time, span count, and slowest span. */
    public record TraceSummary(String traceId, String rootSpan, long totalMs, int spanCount,
            String slowestSpan, long slowestMs) {
    }
}
