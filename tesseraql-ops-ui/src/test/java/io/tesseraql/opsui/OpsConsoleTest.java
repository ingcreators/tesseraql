package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.diag.SqlExecution;
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
