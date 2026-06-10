package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.diag.SqlExecution;
import io.tesseraql.core.telemetry.SpanSample;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import io.tesseraql.operations.batch.StepExecution;
import io.tesseraql.operations.batch.StepStatus;
import io.tesseraql.opsui.OpsDashboard.Alert;
import io.tesseraql.opsui.OpsDashboard.BatchSummary;
import io.tesseraql.opsui.OpsDashboard.ExecutionView;
import io.tesseraql.opsui.OpsDashboard.LaneStatus;
import io.tesseraql.opsui.OpsDashboard.Overview;
import io.tesseraql.opsui.OpsDashboard.PinningSummary;
import io.tesseraql.opsui.OpsDashboard.TraceMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsConsoleTest {

    private static Overview overview(boolean warning, List<Alert> alerts, BatchSummary batch,
            List<LaneStatus> lanes, List<SqlExecution> slowSql, TraceMetrics metrics,
            PinningSummary pinning) {
        return new Overview(batch, lanes, slowSql, List.of(), metrics, pinning, warning, alerts);
    }

    private static TraceMetrics metrics() {
        return new TraceMetrics(10, 1, 10.0, 2, 20.0, 4, 1, 25.0);
    }

    @Test
    void rendersValidDocumentWithAllSections() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        byStatus.put("COMPLETED", 3);
        byStatus.put("FAILED", 1);
        Overview overview = overview(false, List.of(),
                new BatchSummary(4, byStatus, List.of(
                        new ExecutionView("e-1", "nightly", "COMPLETED", "cron", "2026-06-10T00:00:00Z", 1200L))),
                List.of(new LaneStatus("default", "VIRTUAL", 100, 2, 2, 50, 0)),
                List.of(new SqlExecution("web/api/users/search.sql", "query", 350, 42, 0)),
                metrics(), new PinningSummary(0, List.of()));

        String html = OpsConsole.render(overview);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("TesseraQL Operations Console");
        assertThat(html).contains("id=\"batch\"").contains("id=\"lanes\"")
                .contains("id=\"traces\"").contains("id=\"slow-sql\"").contains("id=\"pinning\"");
        assertThat(html).contains("nightly").contains("web/api/users/search.sql");
        assertThat(html).contains("COMPLETED: 3").contains("FAILED: 1");
        assertThat(html).contains("badge ok").doesNotContain("badge warn");
        // The overview links to the execution detail and trace drilldowns.
        assertThat(html).contains("/_tesseraql/ops/console/executions/e-1");
        assertThat(html).contains("/_tesseraql/ops/console/traces");
    }

    @Test
    void rendersExecutionDetailWithSteps() {
        JobExecution execution = new JobExecution("e-9", "nightly", "app", JobStatus.COMPLETED,
                "cron", java.time.Instant.parse("2026-06-10T00:00:00Z"),
                java.time.Instant.parse("2026-06-10T00:00:02Z"), 2000L, "ok");
        StepExecution step = new StepExecution("s-1", "e-9", "load", StepStatus.COMPLETED,
                null, null, 1500L, 42, null);

        String html = OpsConsole.renderExecution(execution, List.of(step));

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("Execution e-9").contains("nightly").contains("COMPLETED");
        assertThat(html).contains("load").contains("42");
        assertThat(html).contains("href=\"/_tesseraql/ops/console\"");
    }

    @Test
    void rendersTraceTreeWithNestedSpans() {
        SpanSample root = new SpanSample("tesseraql.route", "t-1", "s-1", null,
                java.util.Map.of(), 120, false, 0);
        SpanSample child = new SpanSample("tesseraql.sql.execute", "t-1", "s-2", "s-1",
                java.util.Map.of(), 90, true, 0);
        var childNode = new OpsDashboard.TraceNode(child, 90, 90, "1970-01-01T00:00:00Z", true,
                List.of());
        var rootNode = new OpsDashboard.TraceNode(root, 120, 30, "1970-01-01T00:00:00Z", false,
                List.of(childNode));

        String html = OpsConsole.renderTraces(List.of(rootNode));

        assertThat(html).contains("tesseraql.route").contains("tesseraql.sql.execute");
        assertThat(html).contains("120ms total, 30ms self");
        assertThat(html).contains("span-error");
        assertThat(html).contains("class=\"slow\"");
    }

    @Test
    void rendersEmptyTraceTree() {
        assertThat(OpsConsole.renderTraces(List.of())).contains("No traces retained.");
    }

    @Test
    void rendersWarningBadgeAndAlertsWhenPresent() {
        Overview overview = overview(true,
                List.of(new Alert("TQL-OPS-9001", "warning", "Trace error rate 12.0% is high")),
                new BatchSummary(0, Map.of(), List.of()), List.of(), List.of(),
                metrics(), new PinningSummary(0, List.of()));

        String html = OpsConsole.render(overview);

        assertThat(html).contains("badge warn");
        assertThat(html).contains("id=\"alerts\"");
        assertThat(html).contains("TQL-OPS-9001").contains("Trace error rate 12.0% is high");
    }

    @Test
    void rendersEmptyStatesWhenNoData() {
        Overview overview = overview(false, List.of(),
                new BatchSummary(0, Map.of(), List.of()), List.of(), List.of(),
                metrics(), new PinningSummary(0, List.of()));

        String html = OpsConsole.render(overview);

        assertThat(html).contains("No batch executions recorded.");
        assertThat(html).contains("No execution lanes registered.");
        assertThat(html).contains("No slow SQL recorded.");
        assertThat(html).contains("No pinning events detected.");
        // The alerts section is omitted entirely when there are none.
        assertThat(html).doesNotContain("id=\"alerts\"");
    }

    @Test
    void escapesDynamicValues() {
        Overview overview = overview(false,
                List.of(new Alert("X", "warning", "<script>alert(1)</script>")),
                new BatchSummary(1, Map.of(), List.of(
                        new ExecutionView("<id>", "job&\"x\"", "RUNNING", "manual", null, null))),
                List.of(), List.of(), metrics(), new PinningSummary(0, List.of()));

        String html = OpsConsole.render(overview);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;id&gt;").contains("job&amp;&quot;x&quot;");
    }
}
