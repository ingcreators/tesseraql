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
        Map<String, TraceNode> byId = new LinkedHashMap<>();
        for (SpanSample span : spans) {
            boolean slow = span.durationMs() >= slowSpanThresholdMs;
            String startedAt = java.time.Instant.ofEpochMilli(span.startedAtEpochMs()).toString();
            byId.put(span.spanId(), new TraceNode(span, span.durationMs(), startedAt, slow,
                    new java.util.ArrayList<>()));
        }
        List<TraceNode> roots = new java.util.ArrayList<>();
        for (SpanSample span : spans) {
            TraceNode node = byId.get(span.spanId());
            TraceNode parent = span.parentSpanId() == null ? null : byId.get(span.parentSpanId());
            if (parent != null) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
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
     * A span and its child spans, formatted for display: {@code durationMs} and an ISO-8601
     * {@code startedAt} are surfaced for convenience, and {@code slow} flags spans over the
     * configured threshold so the UI can highlight them.
     */
    public record TraceNode(SpanSample span, long durationMs, String startedAt, boolean slow,
            List<TraceNode> children) {
    }
}
