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

    /** Slice 5 filter: route/actor contain (case-insensitive), status starts-with. */
    @Test
    void auditFilterNarrowsByRouteActorAndStatusPrefix() {
        List<Map<String, Object>> rows = List.of(
                Map.of("routeId", "users.create", "actor", "alice", "status", 201),
                Map.of("routeId", "orders.update", "actor", "bob", "status", 422));
        assertThat(OpsViews.filterAudit(rows, "USERS", null, null)).hasSize(1);
        assertThat(OpsViews.filterAudit(rows, null, "BO", null)).hasSize(1);
        assertThat(OpsViews.filterAudit(rows, null, null, "4")).hasSize(1);
        assertThat(OpsViews.filterAudit(rows, "", " ", null)).hasSize(2);
        assertThat(OpsViews.filterAudit(rows, "nope", null, null)).isEmpty();
        assertThat(OpsViews.filterAudit(null, "x", null, null)).isNull();
    }

    @Test
    void auditModelReportsTheDisabledStoreHonestly() {
        Map<String, Object> disabled = OpsViews.audit(null, false);
        assertThat(disabled.get("enabled")).isEqualTo(false);
        assertThat(disabled.get("hasRows")).isEqualTo(false);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("auditId", "a-1");
        row.put("app", "demo-app");
        row.put("routeId", "orders.create");
        row.put("method", "POST");
        row.put("path", "/api/orders");
        row.put("actor", null);
        row.put("status", 422);
        row.put("durationMs", 12L);
        row.put("params", null);
        row.put("traceId", "t-1");
        row.put("occurredAt", "2026-07-26 12:00:00");
        Map<String, Object> enabled = OpsViews.audit(List.of(row), true);
        assertThat(enabled.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) enabled.get("rows");
        assertThat(rows.get(0).get("statusVariant")).isEqualTo("warning");
        assertThat(rows.get(0).get("actor")).isEqualTo("-");
        assertThat(rows.get(0).get("params")).isEqualTo("-");
    }

    @Test
    void overviewBuildsTemplateReadyModel() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        byStatus.put("COMPLETED", 3);
        Overview overview = new Overview(
                new BatchSummary(3, byStatus, List.of(new ExecutionView(
                        "e-1", "nightly", "demo-app", "COMPLETED", "cron",
                        "2026-06-10T00:00:00Z", 1200L))),
                List.of(new LaneStatus("default", "VIRTUAL", 100, 98, 2, 50, 1)),
                List.of(), List.of(), metrics(), new PinningSummary(0, List.of()),
                true, List.of(new Alert("TQL-OPS-9001", "warning", "high error rate")));

        Map<String, Object> model = OpsViews.overview(overview,
                new OpsDashboard.HealthReport("WARN",
                        Map.of("datasources", Map.of("main", true, "reporting", false))),
                "1.2.3");

        assertThat(model.get("warning")).isEqualTo(true);
        assertThat(model.get("version")).isEqualTo("1.2.3");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) model.get("health");
        assertThat(health.get("status")).isEqualTo("WARN");
        assertThat(health.get("statusVariant")).isEqualTo("warning");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> datasources = (List<Map<String, Object>>) health
                .get("datasources");
        assertThat(datasources).anySatisfy(d -> {
            assertThat(d.get("name")).isEqualTo("reporting");
            assertThat(d.get("state")).isEqualTo("unreachable");
            assertThat(d.get("stateVariant")).isEqualTo("error");
        });
        assertThat(model.get("ok")).isEqualTo(false);
        assertThat(model.get("hasAlerts")).isEqualTo(true);
        assertThat(model.get("batchTotal")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) model.get("recent");
        assertThat(recent.get(0).get("id")).isEqualTo("e-1");
        assertThat(recent.get(0).get("statusVariant")).isEqualTo("success");
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
                Map.of("app", "user-admin"), 120, false, 0);
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
                .containsEntry("app", "user-admin")
                .containsEntry("indentPx", 0).containsEntry("selfMs", 30L);
        // Child rows leave the app blank; the trace root carries the attribution.
        assertThat(spans.get(1)).containsEntry("name", "tesseraql.sql.execute")
                .containsEntry("app", "")
                .containsEntry("indentPx", 18).containsEntry("error", true)
                .containsEntry("slow", true);
    }

    @Test
    void jobsRowsCarryTheTriggerStoryPoliciesAndCalendarNext() {
        io.tesseraql.yaml.model.JobDefinition definition = new io.tesseraql.yaml.model.JobDefinition(
                "tesseraql/v1", "nightly.close", "job", "batch-tasklet", null,
                new io.tesseraql.yaml.model.TriggerSpec(
                        new io.tesseraql.yaml.model.TriggerSpec.Schedule(
                                "0 0 8 * * ?", null, "jp-banking", null, 5, null)),
                Map.of(), null, List.of(), false, null, "skip",
                new io.tesseraql.yaml.model.SlaSpec("06:00", "2h"));

        Map<String, Object> model = OpsViews.jobs(List.of(new OpsViews.JobCatalogEntry(
                "nightly.close", "app", definition, null, null, "2026-08-03 (for 2026-07-31)")));

        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) model.get("rows")).get(0);
        assertThat(row.get("trigger"))
                .isEqualTo("cron 0 0 8 * * ?, calendar jp-banking (day 5)");
        assertThat(row.get("policies"))
                .isEqualTo(List.of("overlap: skip", "sla by 06:00", "sla > 2h"));
        assertThat(row.get("calendarNext")).isEqualTo("2026-08-03 (for 2026-07-31)");
    }

    @Test
    void executionBuildsDetailModel() {
        JobExecution execution = new JobExecution("e-9", "nightly", "app", JobStatus.COMPLETED,
                "cron", null, java.time.LocalDate.parse("2026-06-10"),
                java.time.Instant.parse("2026-06-10T00:00:00Z"),
                java.time.Instant.parse("2026-06-10T00:00:02Z"), 2000L, "ok");
        StepExecution step = new StepExecution("s-1", "e-9", "load", StepStatus.FAILED,
                null, null, 1500L, 42, null, "boom");

        Map<String, Object> model = OpsViews.execution("e-9", execution, List.of(step));

        assertThat(model.get("found")).isEqualTo(true);
        assertThat(model.get("jobId")).isEqualTo("nightly");
        assertThat(model.get("statusVariant")).isEqualTo("success");
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

    @Test
    void outboxBuildsDeliveryLogModel() {
        io.tesseraql.core.outbox.OutboxEvent dead = new io.tesseraql.core.outbox.OutboxEvent(
                "evt-1", "Notification", "users.register.confirmation", "NOTIFICATION", "{}",
                "DEAD", 10, "Webhook channel 'hooks' answered HTTP 500",
                java.time.Instant.parse("2026-06-10T00:00:00Z"), null, "demo-app");
        io.tesseraql.core.outbox.OutboxEvent sent = new io.tesseraql.core.outbox.OutboxEvent(
                "evt-2", "User", "sato", "USER_PROVISIONED", "{}", "SENT", 1, null,
                java.time.Instant.parse("2026-06-10T00:00:01Z"),
                java.time.Instant.parse("2026-06-10T00:00:02Z"), "demo-app");

        Map<String, Object> model = OpsViews.outbox(List.of(dead, sent));

        assertThat(model.get("hasRows")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("id", "evt-1")
                .containsEntry("status", "DEAD")
                .containsEntry("statusVariant", "error")
                .containsEntry("attempts", 10)
                .containsEntry("dead", true)
                .containsEntry("lastError", "Webhook channel 'hooks' answered HTTP 500");
        assertThat(rows.get(1))
                .containsEntry("dead", false)
                .containsEntry("sentAt", "2026-06-10T00:00:02Z");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byStatus = (List<Map<String, Object>>) model.get("byStatus");
        assertThat(byStatus).hasSize(2);
    }

    @Test
    void eventsBuildsQueueLogModel() {
        io.tesseraql.core.messaging.ChannelEvent dead = new io.tesseraql.core.messaging.ChannelEvent(
                "msg-1", "events", "orders.created", "O-1", "demo-app", "DEAD", 3,
                "Route queue.orders threw", java.time.Instant.parse("2026-06-10T00:00:00Z"),
                null);
        io.tesseraql.core.messaging.ChannelEvent consumed = new io.tesseraql.core.messaging.ChannelEvent(
                "msg-2", "events", "orders.created", null, "demo-app", "CONSUMED", 1, null,
                java.time.Instant.parse("2026-06-10T00:00:01Z"),
                java.time.Instant.parse("2026-06-10T00:00:02Z"));

        Map<String, Object> model = OpsViews.events(List.of(dead, consumed));

        assertThat(model.get("hasRows")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("id", "msg-1")
                .containsEntry("channel", "events")
                .containsEntry("topic", "orders.created")
                .containsEntry("status", "DEAD")
                .containsEntry("statusVariant", "error")
                .containsEntry("attempts", 3)
                .containsEntry("dead", true)
                .containsEntry("lastError", "Route queue.orders threw");
        assertThat(rows.get(1))
                .containsEntry("dead", false)
                .containsEntry("key", "-")
                .containsEntry("statusVariant", "success")
                .containsEntry("consumedAt", "2026-06-10T00:00:02Z");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byStatus = (List<Map<String, Object>>) model.get("byStatus");
        assertThat(byStatus).hasSize(2);
    }
}
