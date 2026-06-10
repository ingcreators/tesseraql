package io.tesseraql.opsui;

import io.tesseraql.core.diag.SqlExecution;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.StepExecution;
import io.tesseraql.opsui.OpsDashboard.Alert;
import io.tesseraql.opsui.OpsDashboard.ExecutionView;
import io.tesseraql.opsui.OpsDashboard.LaneStatus;
import io.tesseraql.opsui.OpsDashboard.Overview;
import io.tesseraql.opsui.OpsDashboard.TraceMetrics;
import io.tesseraql.opsui.OpsDashboard.TraceNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template-ready views over the operations dashboard (design ch. 26.11, 47): pure mappings from the
 * dashboard records to plain maps, lists and scalars with display fields (status classes, indents,
 * flags) precomputed, served to the bundled ops-console app through the {@code ops.*} service
 * providers.
 */
public final class OpsViews {

    private OpsViews() {
    }

    /** The overview page model: batch summary, lanes, trace metrics, slow SQL, pinning, alerts. */
    public static Map<String, Object> overview(Overview overview) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("warning", overview.warning());
        model.put("ok", !overview.warning());
        model.put("alerts", alerts(overview.alerts()));
        model.put("hasAlerts", !overview.alerts().isEmpty());
        model.put("batchTotal", overview.batch().total());
        model.put("byStatus", byStatus(overview.batch().byStatus()));
        model.put("recent", executions(overview.batch().recent()));
        model.put("hasRecent", !overview.batch().recent().isEmpty());
        model.put("lanes", lanes(overview.lanes()));
        model.put("hasLanes", !overview.lanes().isEmpty());
        model.put("metrics", metrics(overview.traceMetrics()));
        model.put("slowSql", slowSql(overview.slowSql()));
        model.put("hasSlowSql", !overview.slowSql().isEmpty());
        model.put("pinningCount", overview.pinning().count());
        model.put("hasPinning", overview.pinning().count() > 0);
        return model;
    }

    /** The trace page model: the trace tree flattened with per-node indents. */
    public static Map<String, Object> traces(List<TraceNode> tree) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TraceNode root : tree) {
            flatten(root, 0, rows);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("spans", rows);
        model.put("hasSpans", !rows.isEmpty());
        return model;
    }

    /** The execution detail model, or a not-found shape when {@code execution} is null. */
    public static Map<String, Object> execution(String id, JobExecution execution,
            List<StepExecution> stepExecutions) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id == null ? "" : id);
        if (execution == null) {
            model.put("found", false);
            return model;
        }
        model.put("found", true);
        model.put("jobId", execution.jobId());
        model.put("app", dash(execution.appName()));
        model.put("status", name(execution.status()));
        model.put("statusClass", cssClass(name(execution.status())));
        model.put("trigger", dash(execution.triggerType()));
        model.put("startTime", execution.startTime() == null ? "-" : execution.startTime().toString());
        model.put("endTime", execution.endTime() == null ? "-" : execution.endTime().toString());
        model.put("durationMs", execution.durationMs() == null ? "-"
                : String.valueOf(execution.durationMs()));
        model.put("exitMessage", dash(execution.exitMessage()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepExecution step : stepExecutions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stepId", step.stepId());
            row.put("status", name(step.status()));
            row.put("statusClass", cssClass(name(step.status())));
            row.put("failed", step.status() != null && "FAILED".equals(step.status().name()));
            row.put("affectedRows", step.affectedRows() == null ? "-"
                    : String.valueOf(step.affectedRows()));
            row.put("durationMs", step.durationMs() == null ? "-" : String.valueOf(step.durationMs()));
            row.put("errorMessage", dash(step.errorMessage()));
            steps.add(row);
        }
        model.put("steps", steps);
        model.put("hasSteps", !steps.isEmpty());
        return model;
    }

    private static void flatten(TraceNode node, int depth, List<Map<String, Object>> into) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", node.span().name());
        row.put("indentPx", depth * 18);
        row.put("totalMs", node.durationMs());
        row.put("selfMs", node.selfMs());
        row.put("slow", node.slow());
        row.put("error", node.span().error());
        into.add(row);
        for (TraceNode child : node.children()) {
            flatten(child, depth + 1, into);
        }
    }

    private static List<Map<String, Object>> alerts(List<Alert> alerts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Alert alert : alerts) {
            rows.add(Map.of("code", alert.code(), "severity", alert.severity(),
                    "message", alert.message()));
        }
        return rows;
    }

    private static List<Map<String, Object>> byStatus(Map<String, Integer> byStatus) {
        List<Map<String, Object>> rows = new ArrayList<>();
        byStatus.forEach((status, count) -> rows.add(Map.of(
                "status", status, "count", count, "statusClass", cssClass(status))));
        return rows;
    }

    private static List<Map<String, Object>> executions(List<ExecutionView> recent) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExecutionView view : recent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", view.id());
            row.put("jobId", dash(view.jobId()));
            row.put("app", dash(view.app()));
            row.put("status", dash(view.status()));
            row.put("statusClass", cssClass(view.status()));
            row.put("trigger", dash(view.trigger()));
            row.put("startTime", dash(view.startTime()));
            row.put("durationMs", view.durationMs() == null ? "-" : String.valueOf(view.durationMs()));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> lanes(List<LaneStatus> lanes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LaneStatus lane : lanes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", lane.name());
            row.put("type", lane.type());
            row.put("inUse", lane.inUse());
            row.put("max", lane.maxConcurrency());
            row.put("admitted", lane.admitted());
            row.put("rejected", lane.rejected());
            row.put("warn", lane.rejected() > 0);
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> metrics(TraceMetrics metrics) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("spans", metrics.spans());
        row.put("spanErrorRate", metrics.spanErrorRate());
        row.put("slowRate", metrics.slowRate());
        row.put("traces", metrics.traces());
        row.put("traceErrorRate", metrics.traceErrorRate());
        return row;
    }

    private static List<Map<String, Object>> slowSql(List<SqlExecution> executions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SqlExecution sql : executions) {
            rows.add(Map.of("sqlId", sql.sqlId(), "mode", sql.mode(),
                    "durationMs", sql.durationMs(), "rowCount", sql.rowCount()));
        }
        return rows;
    }

    private static String name(Enum<?> value) {
        return value == null ? "-" : value.name();
    }

    private static String cssClass(String status) {
        return status == null ? "" : "status-" + status.toLowerCase(java.util.Locale.ROOT);
    }

    private static String dash(String value) {
        return value == null ? "-" : value;
    }
}
