package io.tesseraql.opsui;

import io.tesseraql.core.diag.SqlExecution;
import io.tesseraql.core.template.HtmlTemplateEngine;
import io.tesseraql.opsui.OpsDashboard.Alert;
import io.tesseraql.opsui.OpsDashboard.BatchSummary;
import io.tesseraql.opsui.OpsDashboard.ExecutionView;
import io.tesseraql.opsui.OpsDashboard.LaneStatus;
import io.tesseraql.opsui.OpsDashboard.Overview;
import io.tesseraql.opsui.OpsDashboard.PinningSummary;
import io.tesseraql.opsui.OpsDashboard.TraceMetrics;
import java.util.Map;

/**
 * Renders the {@link OpsDashboard} overview as a self-contained, read-only HTML page for the
 * Operations Console UI (design ch. 26.11, 16). The markup is a valid Light DOM document with
 * inlined styles and no external resources, so it serves under a strict {@code default-src 'self'}
 * content security policy. All dynamic values are HTML-escaped.
 */
public final class OpsConsole {

    private OpsConsole() {
    }

    /** Renders the full Operations Console page for a dashboard overview snapshot. */
    public static String render(Overview overview) {
        StringBuilder out = new StringBuilder(4096);
        out.append("<!DOCTYPE html>\n")
                .append("<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>TesseraQL Operations Console</title>\n")
                .append("<style>").append(styles()).append("</style>\n")
                .append("</head>\n<body>\n");

        out.append("<header class=\"topbar\"><h1>TesseraQL Operations Console</h1>")
                .append(statusBadge(overview.warning()))
                .append("</header>\n");
        out.append("<main>\n");

        renderAlerts(out, overview);
        renderBatch(out, overview.batch());
        renderLanes(out, overview);
        renderTraceMetrics(out, overview.traceMetrics());
        renderSlowSql(out, overview);
        renderPinning(out, overview.pinning());

        out.append("</main>\n</body>\n</html>\n");
        return out.toString();
    }

    private static void renderAlerts(StringBuilder out, Overview overview) {
        if (overview.alerts().isEmpty()) {
            return;
        }
        section(out, "alerts", "Alerts");
        out.append("<ul class=\"alerts\">");
        for (Alert alert : overview.alerts()) {
            out.append("<li class=\"alert ").append(escape(alert.severity())).append("\">")
                    .append("<span class=\"code\">").append(escape(alert.code())).append("</span> ")
                    .append(escape(alert.message()))
                    .append("</li>");
        }
        out.append("</ul></section>\n");
    }

    private static void renderBatch(StringBuilder out, BatchSummary batch) {
        section(out, "batch", "Batch executions");
        out.append("<p class=\"summary\">Total scanned: <strong>").append(batch.total())
                .append("</strong></p>");
        if (!batch.byStatus().isEmpty()) {
            out.append("<ul class=\"chips\">");
            for (Map.Entry<String, Integer> entry : batch.byStatus().entrySet()) {
                out.append("<li class=\"chip status-").append(escape(entry.getKey().toLowerCase()))
                        .append("\">").append(escape(entry.getKey())).append(": ")
                        .append(entry.getValue()).append("</li>");
            }
            out.append("</ul>");
        }
        if (batch.recent().isEmpty()) {
            empty(out, "No batch executions recorded.");
        } else {
            out.append("<table><thead><tr><th>Execution</th><th>Job</th><th>Status</th>")
                    .append("<th>Trigger</th><th>Started</th><th>Duration (ms)</th></tr></thead><tbody>");
            for (ExecutionView execution : batch.recent()) {
                out.append("<tr>")
                        .append(td(execution.id()))
                        .append(td(execution.jobId()))
                        .append("<td class=\"status-").append(escape(nullToDash(execution.status())
                                .toLowerCase())).append("\">").append(escape(execution.status()))
                        .append("</td>")
                        .append(td(execution.trigger()))
                        .append(td(execution.startTime()))
                        .append(td(execution.durationMs() == null ? null
                                : String.valueOf(execution.durationMs())))
                        .append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n");
    }

    private static void renderLanes(StringBuilder out, Overview overview) {
        section(out, "lanes", "Execution lanes");
        if (overview.lanes().isEmpty()) {
            empty(out, "No execution lanes registered.");
        } else {
            out.append("<table><thead><tr><th>Lane</th><th>Type</th><th>In use</th>")
                    .append("<th>Max</th><th>Admitted</th><th>Rejected</th></tr></thead><tbody>");
            for (LaneStatus lane : overview.lanes()) {
                out.append(lane.rejected() > 0 ? "<tr class=\"warn\">" : "<tr>")
                        .append(td(lane.name()))
                        .append(td(lane.type()))
                        .append(td(String.valueOf(lane.inUse())))
                        .append(td(String.valueOf(lane.maxConcurrency())))
                        .append(td(String.valueOf(lane.admitted())))
                        .append(td(String.valueOf(lane.rejected())))
                        .append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n");
    }

    private static void renderTraceMetrics(StringBuilder out, TraceMetrics metrics) {
        section(out, "traces", "Trace metrics");
        out.append("<ul class=\"chips\">")
                .append(metricChip("Spans", String.valueOf(metrics.spans())))
                .append(metricChip("Span error rate", metrics.spanErrorRate() + "%"))
                .append(metricChip("Slow span rate", metrics.slowRate() + "%"))
                .append(metricChip("Traces", String.valueOf(metrics.traces())))
                .append(metricChip("Trace error rate", metrics.traceErrorRate() + "%"))
                .append("</ul></section>\n");
    }

    private static void renderSlowSql(StringBuilder out, Overview overview) {
        section(out, "slow-sql", "Slow SQL");
        if (overview.slowSql().isEmpty()) {
            empty(out, "No slow SQL recorded.");
        } else {
            out.append("<table><thead><tr><th>SQL id</th><th>Mode</th>")
                    .append("<th>Duration (ms)</th><th>Rows</th></tr></thead><tbody>");
            for (SqlExecution sql : overview.slowSql()) {
                out.append("<tr>")
                        .append(td(sql.sqlId()))
                        .append(td(sql.mode()))
                        .append(td(String.valueOf(sql.durationMs())))
                        .append(td(String.valueOf(sql.rowCount())))
                        .append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n");
    }

    private static void renderPinning(StringBuilder out, PinningSummary pinning) {
        section(out, "pinning", "Virtual-thread pinning");
        if (pinning.count() == 0) {
            empty(out, "No pinning events detected.");
        } else {
            out.append("<p class=\"summary\">Pinning events: <strong>").append(pinning.count())
                    .append("</strong></p>");
        }
        out.append("</section>\n");
    }

    private static void section(StringBuilder out, String id, String title) {
        out.append("<section id=\"").append(escape(id)).append("\"><h2>")
                .append(escape(title)).append("</h2>");
    }

    private static String statusBadge(boolean warning) {
        return warning
                ? "<span class=\"badge warn\">WARN</span>"
                : "<span class=\"badge ok\">UP</span>";
    }

    private static String metricChip(String label, String value) {
        return "<li class=\"chip\">" + escape(label) + ": <strong>" + escape(value)
                + "</strong></li>";
    }

    private static void empty(StringBuilder out, String message) {
        out.append("<p class=\"empty\">").append(escape(message)).append("</p>");
    }

    private static String td(String value) {
        return "<td>" + escape(nullToDash(value)) + "</td>";
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    private static String escape(String value) {
        return HtmlTemplateEngine.escape(value == null ? "" : value);
    }

    private static String styles() {
        return "*{box-sizing:border-box}"
                + "body{margin:0;font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0}"
                + ".topbar{display:flex;align-items:center;justify-content:space-between;"
                + "padding:16px 24px;background:#1e293b;border-bottom:1px solid #334155}"
                + ".topbar h1{font-size:18px;margin:0}"
                + "main{padding:24px;max-width:1100px;margin:0 auto}"
                + "section{background:#1e293b;border:1px solid #334155;border-radius:8px;"
                + "padding:16px 20px;margin-bottom:20px}"
                + "h2{font-size:15px;margin:0 0 12px;color:#93c5fd}"
                + "table{width:100%;border-collapse:collapse;font-size:13px}"
                + "th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #334155}"
                + "th{color:#94a3b8;font-weight:600}"
                + ".chips{list-style:none;padding:0;margin:0;display:flex;flex-wrap:wrap;gap:8px}"
                + ".chip{background:#334155;border-radius:999px;padding:4px 12px;font-size:12px}"
                + ".badge{padding:4px 12px;border-radius:999px;font-size:12px;font-weight:700}"
                + ".badge.ok{background:#166534;color:#dcfce7}"
                + ".badge.warn{background:#9a3412;color:#ffedd5}"
                + ".alerts{list-style:none;padding:0;margin:0}"
                + ".alert{padding:8px 12px;border-radius:6px;margin-bottom:6px;background:#7f1d1d}"
                + ".alert .code{font-family:monospace;opacity:.8;margin-right:6px}"
                + ".empty{color:#94a3b8;font-style:italic;margin:0}"
                + ".summary{margin:0 0 12px}"
                + "tr.warn td{background:#7f1d1d33}"
                + ".status-completed{color:#86efac}.status-failed{color:#fca5a5}"
                + ".status-running{color:#93c5fd}";
    }
}
