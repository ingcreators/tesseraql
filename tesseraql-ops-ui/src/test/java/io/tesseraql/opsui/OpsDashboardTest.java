package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.telemetry.RingTracer;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.LanePolicy;
import io.tesseraql.opsui.OpsDashboard.LaneStatus;
import io.tesseraql.opsui.OpsDashboard.TraceNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsDashboardTest {

    @Test
    void reportsAllLanesWithTheirTypes() {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(
                LanePolicy.virtual("io", 4), LanePolicy.platform("cpu", 2)))) {
            assertThat(OpsDashboard.laneStatuses(lanes))
                    .extracting(LaneStatus::name, LaneStatus::type)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("io", "VIRTUAL"),
                            org.assertj.core.groups.Tuple.tuple("cpu", "PLATFORM"));
        }
    }

    @Test
    void laneStatusReflectsCapacityAndUsage() {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 3)))) {
            lanes.lane("io").tryAdmit();
            lanes.lane("io").tryAdmit();

            List<LaneStatus> statuses = OpsDashboard.laneStatuses(lanes);
            assertThat(statuses).singleElement().satisfies(status -> {
                assertThat(status.name()).isEqualTo("io");
                assertThat(status.type()).isEqualTo("VIRTUAL");
                assertThat(status.maxConcurrency()).isEqualTo(3);
                assertThat(status.inUse()).isEqualTo(2);
                assertThat(status.available()).isEqualTo(1);
                assertThat(status.admitted()).isEqualTo(2);
                assertThat(status.rejected()).isZero();
            });
        }
    }

    @Test
    void traceTreeNestsChildSpansUnderTheirParent() {
        RingTracer tracer = new RingTracer(10);
        Span route = tracer.start("tesseraql.route");
        Span sql = tracer.start("tesseraql.sql.execute", route.context());
        sql.end();
        route.end();

        OpsDashboard dashboard = new OpsDashboard(null, null, null, tracer, 200L);
        List<TraceNode> roots = dashboard.traceTree();

        assertThat(roots).singleElement().satisfies(root -> {
            assertThat(root.span().name()).isEqualTo("tesseraql.route");
            assertThat(root.startedAt()).isNotBlank();
            assertThat(root.durationMs()).isEqualTo(root.span().durationMs());
            assertThat(root.children()).singleElement().satisfies(child -> {
                assertThat(child.span().name()).isEqualTo("tesseraql.sql.execute");
                assertThat(child.span().traceId()).isEqualTo(root.span().traceId());
                assertThat(child.span().parentSpanId()).isEqualTo(root.span().spanId());
            });
        });
    }

    @Test
    void traceSummaryReportsTotalCountAndSlowest() {
        RingTracer tracer = new RingTracer(10);
        Span route = tracer.start("tesseraql.route");
        tracer.start("tesseraql.security.authenticate", route.context()).end();
        tracer.start("tesseraql.sql.execute", route.context()).end();
        route.end();

        OpsDashboard dashboard = new OpsDashboard(null, null, null, tracer, 200L);

        assertThat(dashboard.traceTree()).singleElement().satisfies(root ->
                // self time excludes children (here children are ~0ms, so selfMs ~ durationMs).
                assertThat(root.selfMs()).isLessThanOrEqualTo(root.durationMs()));
        assertThat(dashboard.traceSummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.rootSpan()).isEqualTo("tesseraql.route");
            assertThat(summary.spanCount()).isEqualTo(3);
            assertThat(summary.slowestSpan()).isNotBlank();
            assertThat(summary.traceId()).isNotBlank();
        });
    }

    @Test
    void traceSummaryCountsErrorsAndSlowSpansAndFilters() {
        RingTracer tracer = new RingTracer(10);
        // Trace A: a root with one failing child span.
        Span rootA = tracer.start("tesseraql.route");
        Span failing = tracer.start("tesseraql.sql.execute", rootA.context());
        failing.recordError(new RuntimeException("boom"));
        failing.end();
        rootA.end();
        // Trace B: a clean root with one healthy child.
        Span rootB = tracer.start("tesseraql.route");
        tracer.start("tesseraql.sql.execute", rootB.context()).end();
        rootB.end();

        // Threshold above any real duration so nothing is "slow"; only errors distinguish traces.
        OpsDashboard dashboard = new OpsDashboard(null, null, null, tracer, 1_000_000L);

        assertThat(dashboard.traceSummaries()).hasSize(2);
        assertThat(dashboard.traceSummaries("errors"))
                .singleElement().satisfies(summary -> assertThat(summary.errorCount()).isEqualTo(1));
        // With the threshold low, every span is slow, so the slow filter keeps both traces.
        OpsDashboard slowDashboard = new OpsDashboard(null, null, null, tracer, 0L);
        assertThat(slowDashboard.traceSummaries("slow")).hasSize(2);
        assertThat(slowDashboard.traceSummaries("slow"))
                .allSatisfy(summary -> assertThat(summary.slowCount()).isGreaterThan(0));
    }

    @Test
    void slowFlagHighlightsSpansOverThreshold() {
        RingTracer tracer = new RingTracer(10);
        tracer.start("tesseraql.route").end();

        // Threshold 0: every span (>= 0 ms) is flagged slow.
        assertThat(new OpsDashboard(null, null, null, tracer, 0L).traceTree())
                .singleElement().satisfies(root -> assertThat(root.slow()).isTrue());
        // A very high threshold: nothing is flagged.
        assertThat(new OpsDashboard(null, null, null, tracer, 1_000_000L).traceTree())
                .singleElement().satisfies(root -> assertThat(root.slow()).isFalse());
    }

    @Test
    void rejectionsAreCounted() {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 1)))) {
            assertThat(lanes.lane("io").tryAdmit()).isTrue();
            assertThat(lanes.lane("io").tryAdmit()).isFalse();

            assertThat(OpsDashboard.laneStatuses(lanes)).singleElement().satisfies(status -> {
                assertThat(status.inUse()).isEqualTo(1);
                assertThat(status.rejected()).isEqualTo(1);
            });
        }
    }
}
