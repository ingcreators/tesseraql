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
 * dashboard records to plain maps, lists and scalars with display fields (status variants, indents,
 * flags) precomputed, served to the bundled ops-console app through the {@code ops.*} service
 * providers.
 */
public final class OpsViews {

    private OpsViews() {
    }

    /**
     * The overview page model: batch summary, lanes, trace metrics, slow SQL, pinning,
     * alerts — and the health roll-up with its per-datasource probe map plus the deployed
     * version (docs/ops-console-coverage.md).
     */
    public static Map<String, Object> overview(Overview overview,
            io.tesseraql.opsui.OpsDashboard.HealthReport health, String version) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("warning", overview.warning());
        model.put("ok", !overview.warning());
        model.put("health", health(health));
        model.put("version", version == null || version.isBlank() ? "dev" : version);
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

    /** The health panel: the roll-up badge plus each datasource probe as its own badge. */
    private static Map<String, Object> health(io.tesseraql.opsui.OpsDashboard.HealthReport health) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("status", health.status());
        model.put("statusVariant", switch (health.status()) {
            case "UP" -> "success";
            case "WARN" -> "warning";
            default -> "error";
        });
        List<Map<String, Object>> datasources = new ArrayList<>();
        if (health.details().get("datasources") instanceof Map<?, ?> probes) {
            probes.forEach((name, up) -> datasources.add(Map.of(
                    "name", String.valueOf(name),
                    "state", Boolean.TRUE.equals(up) ? "reachable" : "unreachable",
                    "stateVariant", Boolean.TRUE.equals(up) ? "success" : "error")));
        }
        model.put("datasources", datasources);
        model.put("hasDatasources", !datasources.isEmpty());
        return model;
    }

    /**
     * Narrows the audit window by route id / actor (contains, case-insensitive) and status
     * (starts-with, so "4" matches every client error). The filter runs over the fetched
     * newest-200 window, not the whole store — the page says so
     * (docs/console-ux-refresh.md slice 5).
     */
    public static List<Map<String, Object>> filterAudit(List<Map<String, Object>> rows,
            Object route, Object actor, Object status) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .filter(row -> containsIgnoreCase(row.get("routeId"), route))
                .filter(row -> containsIgnoreCase(row.get("actor"), actor))
                .filter(row -> status == null || String.valueOf(status).isBlank()
                        || String.valueOf(row.get("status"))
                                .startsWith(String.valueOf(status).trim()))
                .toList();
    }

    private static boolean containsIgnoreCase(Object value, Object needle) {
        if (needle == null || String.valueOf(needle).isBlank()) {
            return true;
        }
        return value != null && String.valueOf(value).toLowerCase()
                .contains(String.valueOf(needle).trim().toLowerCase());
    }

    /**
     * The audit page model (docs/ops-console-coverage.md): {@code enabled} reports whether
     * the flag-gated store is on, so the empty state can name the flag instead of
     * pretending nothing happened. Rows come pre-scoped from the store.
     */
    public static Map<String, Object> audit(List<Map<String, Object>> rows, boolean enabled) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Map<String, Object> view = new LinkedHashMap<>(row);
                Object status = row.get("status");
                view.put("status", status == null ? "-" : status);
                view.put("statusVariant", httpVariant(status));
                view.put("actor", dash((String) row.get("actor")));
                view.put("tenantId", dash((String) row.get("tenantId")));
                view.put("traceId", dash((String) row.get("traceId")));
                view.put("params", dash((String) row.get("params")));
                out.add(view);
            }
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("enabled", enabled);
        model.put("rows", out);
        model.put("hasRows", !out.isEmpty());
        return model;
    }

    /** Maps an HTTP status onto the kit's semantic variants (5xx error, 4xx warning). */
    private static String httpVariant(Object status) {
        if (!(status instanceof Number number)) {
            return "neutral";
        }
        int value = number.intValue();
        return value >= 500 ? "error" : value >= 400 ? "warning" : "success";
    }

    /** The file transfer page model: recent imports/exports, already scope-filtered. */
    public static Map<String, Object> transfers(
            List<io.tesseraql.core.files.FileTransferService.TransferSummary> transfers) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (io.tesseraql.core.files.FileTransferService.TransferSummary transfer : transfers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", transfer.transferId());
            row.put("route", transfer.routeId());
            row.put("app", dash(transfer.appName()));
            row.put("direction", transfer.direction());
            row.put("format", transfer.format());
            row.put("status", dash(transfer.status()));
            row.put("statusVariant", variant(transfer.status()));
            row.put("rows", transfer.rows());
            row.put("filename", dash(transfer.filename()));
            // A completed export is fetchable through the ops download endpoint — the
            // retrieval path for job-produced files, which have no route-scoped URL
            // (docs/analytics-experience.md track 3).
            row.put("downloadHref", "EXPORT".equals(transfer.direction())
                    && "COMPLETED".equals(transfer.status()) && !transfer.expired()
                            ? "/_tesseraql/ops/console/transfers/" + transfer.transferId()
                                    + "/file"
                            : null);
            // Retention reclaimed the bytes; the row stays as history and says so.
            row.put("expired", transfer.expired());
            row.put("downloaded", transfer.downloaded() ? "yes" : "-");
            row.put("createdAt", transfer.createdAt() == null
                    ? "-"
                    : transfer.createdAt().toString());
            rows.add(row);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("rows", rows);
        model.put("hasRows", !rows.isEmpty());
        return model;
    }

    /**
     * The outbox delivery log model (roadmap Phase 20): recent events with status, attempts and
     * last error, already scope-filtered, plus per-status counts over those rows. Dead-lettered
     * rows carry a {@code dead} flag so the screen can offer redelivery.
     */
    public static Map<String, Object> outbox(List<io.tesseraql.core.outbox.OutboxEvent> events) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (io.tesseraql.core.outbox.OutboxEvent event : events) {
            counts.merge(event.status(), 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", event.id());
            row.put("type", event.eventType());
            row.put("source", dash(event.aggregateId()));
            row.put("app", dash(event.appName()));
            row.put("status", dash(event.status()));
            row.put("statusVariant", variant(event.status()));
            row.put("attempts", event.attempts());
            row.put("lastError", dash(event.lastError()));
            row.put("dead", "DEAD".equals(event.status()));
            row.put("createdAt", event.createdAt() == null ? "-" : event.createdAt().toString());
            row.put("sentAt", event.sentAt() == null ? "-" : event.sentAt().toString());
            rows.add(row);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("rows", rows);
        model.put("hasRows", !rows.isEmpty());
        model.put("byStatus", byStatus(counts));
        return model;
    }

    /**
     * One jobs-catalog row (docs/ops-console-actions.md): the declared job, its owning app,
     * its definition (trigger, params, and the operational policies), its most recent
     * execution (null when it has never run), and — for a calendar-qualified schedule — the
     * next date the calendar lets a firing count, computed by the runtime.
     */
    public record JobCatalogEntry(String id, String app,
            io.tesseraql.yaml.model.JobDefinition definition, JobExecution lastExecution,
            PollSourceStatus.SourceState pollSource, String calendarNext) {
    }

    /** The jobs page model: the scope-filtered catalog with each job's last run summarized. */
    public static Map<String, Object> jobs(List<JobCatalogEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JobCatalogEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("app", dash(entry.app()));
            row.put("trigger",
                    io.tesseraql.yaml.model.TriggerSpec.describe(entry.definition().trigger()));
            // The operational promises (docs/jobs.md): visible where the operator watches,
            // because a calendar-filtered or overlap-skipped firing leaves no execution row.
            List<String> policies = new ArrayList<>();
            if (entry.definition().skipsOverlap()) {
                policies.add("overlap: skip");
            }
            io.tesseraql.yaml.model.SlaSpec sla = entry.definition().sla();
            if (sla != null && sla.completeBy() != null && !sla.completeBy().isBlank()) {
                policies.add("sla by " + sla.completeBy());
            }
            if (sla != null && sla.runningLongerThan() != null
                    && !sla.runningLongerThan().isBlank()) {
                policies.add("sla > " + sla.runningLongerThan());
            }
            row.put("policies", policies);
            row.put("hasPolicies", !policies.isEmpty());
            row.put("calendarNext", dash(entry.calendarNext()));
            JobExecution last = entry.lastExecution();
            row.put("hasRun", last != null);
            row.put("lastStatus", last == null ? "-" : name(last.status()));
            row.put("lastStatusVariant", last == null ? "neutral" : variant(name(last.status())));
            row.put("lastStart",
                    last == null || last.startTime() == null ? "-" : last.startTime().toString());
            row.put("lastExecutionId", last == null ? null : last.id());
            // The declared-params form (docs/ops-console-coverage.md): name, required,
            // and a numeric input for number params; bindJobParams stays the validator.
            List<Map<String, Object>> params = new ArrayList<>();
            entry.definition().params().forEach((name, field) -> params.add(Map.of(
                    "name", name,
                    "required", field != null && field.required(),
                    "numeric", field != null && "number".equals(field.type()))));
            row.put("params", params);
            row.put("hasParams", !params.isEmpty());
            PollSourceStatus.SourceState poll = entry.pollSource();
            row.put("hasPollSource", poll != null);
            if (poll != null) {
                row.put("pollState", poll.skipped() ? "not polling" : "polling");
                row.put("pollVariant", poll.skipped()
                        ? "error"
                        : poll.consecutiveFailures() >= PollSourceStatus.FAILURE_ALERT_THRESHOLD
                                ? "warning"
                                : "success");
                row.put("pollDetail", poll.skipped()
                        ? poll.reason()
                        : poll.lastPollAt() == null
                                ? "no files yet"
                                : poll.lastResult() + " at " + poll.lastPollAt());
            }
            rows.add(row);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("rows", rows);
        model.put("hasRows", !rows.isEmpty());
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
        model.put("statusVariant", variant(name(execution.status())));
        model.put("trigger", dash(execution.triggerType()));
        model.put("triggeredBy", dash(execution.triggeredBy()));
        model.put("startTime",
                execution.startTime() == null ? "-" : execution.startTime().toString());
        model.put("endTime", execution.endTime() == null ? "-" : execution.endTime().toString());
        model.put("durationMs", execution.durationMs() == null
                ? "-"
                : String.valueOf(execution.durationMs()));
        model.put("exitMessage", dash(execution.exitMessage()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepExecution step : stepExecutions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stepId", step.stepId());
            row.put("status", name(step.status()));
            row.put("statusVariant", variant(name(step.status())));
            row.put("failed", step.status() != null && "FAILED".equals(step.status().name()));
            row.put("affectedRows", step.affectedRows() == null
                    ? "-"
                    : String.valueOf(step.affectedRows()));
            // Skips are only a chunk step's concern; the dash keeps other steps quiet.
            row.put("skippedRows",
                    step.skippedRows() == null || step.skippedRows() == 0
                            ? "-"
                            : String.valueOf(step.skippedRows()));
            row.put("durationMs",
                    step.durationMs() == null ? "-" : String.valueOf(step.durationMs()));
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
        // App attribution is shown on the trace root; child spans inherit it visually.
        Object app = node.span().attributes().get("app");
        row.put("app", depth == 0 && app != null ? String.valueOf(app) : "");
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
                "status", status, "count", count, "statusVariant", variant(status))));
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
            row.put("statusVariant", variant(view.status()));
            row.put("trigger", dash(view.trigger()));
            row.put("startTime", dash(view.startTime()));
            row.put("durationMs",
                    view.durationMs() == null ? "-" : String.valueOf(view.durationMs()));
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

    /** Maps an execution/delivery status onto the kit's semantic status variants. */
    private static String variant(String status) {
        if (status == null) {
            return "neutral";
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED", "SENT", "ACTIVE" -> "success";
            case "FAILED", "DEAD" -> "error";
            case "RUNNING", "SENDING", "PENDING" -> "info";
            case "STOPPED" -> "warning";
            default -> "neutral";
        };
    }

    private static String dash(String value) {
        return value == null ? "-" : value;
    }
}
