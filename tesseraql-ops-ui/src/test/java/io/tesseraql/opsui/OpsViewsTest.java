package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.tesseraql.opsui.OpsDashboard.TraceNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsViewsTest {

    private static TraceMetrics metrics() {
        return new TraceMetrics(10, 1, 10.0, 2, 20.0, 4, 1, 25.0);
    }

    @Test
    void overviewBuildsTemplateReadyModel() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        byStatus.put("COMPLETED", 3);
        Overview overview = new Overview(
                new BatchSummary(3, byStatus, List.of(new ExecutionView(
                        "e-1", "nightly", "COMPLETED", "cron", "2026-06-10T00:00:00Z", 1200L))),
                List.of(new LaneStatus("default", "VIRTUAL", 100, 98, 2, 50, 1)),
                List.of(), List.of(), metrics(), new PinningSummary(0, List.of()),
                true, List.of(new Alert("TQL-OPS-9001", "warning", "high error rate")));

        Map<String, Object> model = OpsViews.overview(overview);

        assertThat(model.get("warning")).isEqualTo(true);
        assertThat(model.get("ok")).isEqualTo(false);
        assertThat(model.get("hasAlerts")).isEqualTo(true);
        assertThat(model.get("batchTotal")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) model.get("recent");
        assertThat(recent.get(0).get("id")).isEqualTo("e-1");
        assertThat(recent.get(0).get("statusClass")).isEqualTo("status-completed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lanes = (List<Map<String, Object>>) model.get("lanes");
        assertThat(lanes.get(0).get("warn")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> traceMetrics = (Map<String, Object>) model.get("metrics");
        assertThat(traceMetrics.get("spans")).isEqualTo(10);
        assertThat(model.get("hasSlowSql")).isEqualTo(false);
        assertThat(model.get("hasPinning")).isEqualTo(false);
    }

    @Test
    void tracesFlattenTreeWithIndents() {
        SpanSample rootSpan = new SpanSample("tesseraql.route", "t1", "s1", null,
                Map.of(), 120, false, 0);
        SpanSample childSpan = new SpanSample("tesseraql.sql.execute", "t1", "s2", "s1",
                Map.of(), 90, true, 0);
        TraceNode child = new TraceNode(childSpan, 90, 90, "1970-01-01T00:00:00Z", true, List.of());
        TraceNode root = new TraceNode(rootSpan, 120, 30, "1970-01-01T00:00:00Z", false,
                List.of(child));

        Map<String, Object> model = OpsViews.traces(List.of(root));

        assertThat(model.get("hasSpans")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) model.get("spans");
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0)).containsEntry("name", "tesseraql.route")
                .containsEntry("indentPx", 0).containsEntry("selfMs", 30L);
        assertThat(spans.get(1)).containsEntry("name", "tesseraql.sql.execute")
                .containsEntry("indentPx", 18).containsEntry("error", true)
                .containsEntry("slow", true);
    }

    @Test
    void executionBuildsDetailModel() {
        JobExecution execution = new JobExecution("e-9", "nightly", "app", JobStatus.COMPLETED,
                "cron", java.time.Instant.parse("2026-06-10T00:00:00Z"),
                java.time.Instant.parse("2026-06-10T00:00:02Z"), 2000L, "ok");
        StepExecution step = new StepExecution("s-1", "e-9", "load", StepStatus.FAILED,
                null, null, 1500L, 42, "boom");

        Map<String, Object> model = OpsViews.execution("e-9", execution, List.of(step));

        assertThat(model.get("found")).isEqualTo(true);
        assertThat(model.get("jobId")).isEqualTo("nightly");
        assertThat(model.get("statusClass")).isEqualTo("status-completed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) model.get("steps");
        assertThat(steps.get(0)).containsEntry("stepId", "load")
                .containsEntry("failed", true).containsEntry("errorMessage", "boom");
    }

    @Test
    void unknownExecutionYieldsNotFoundModel() {
        Map<String, Object> model = OpsViews.execution("missing", null, List.of());

        assertThat(model.get("found")).isEqualTo(false);
        assertThat(model.get("id")).isEqualTo("missing");
    }
}
