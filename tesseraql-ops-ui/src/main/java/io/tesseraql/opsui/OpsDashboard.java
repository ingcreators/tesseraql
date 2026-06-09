package io.tesseraql.opsui;

import io.tesseraql.core.diag.SqlExecution;
import io.tesseraql.core.diag.SqlExecutionLog;
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

    public OpsDashboard(JobRepository jobs, ExecutionLanes lanes, SqlExecutionLog slowSql) {
        this.jobs = jobs;
        this.lanes = lanes;
        this.slowSql = slowSql;
    }

    /** Builds the dashboard overview: batch summary, lane diagnostics, and the slow-SQL log. */
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
                laneStatuses(lanes), slowSql.recent());
    }

    /** The recent slow SQL executions collected in-process. */
    public List<SqlExecution> slowSql() {
        return slowSql.recent();
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
    public record Overview(BatchSummary batch, List<LaneStatus> lanes, List<SqlExecution> slowSql) {
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
}
