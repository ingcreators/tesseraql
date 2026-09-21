package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.telemetry.AggregatingMeter;
import io.tesseraql.core.telemetry.PrometheusTextFormat;
import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.LanePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The edge's capacity families render with the labels docs/deployment-maturity.md decision 7
 * names, and the refusal counter sums for the sustained-rate alert.
 */
class EdgeMetricsTest {

    @Test
    void lanesRenderInUseAndRejectedByName() {
        ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 1)));
        try {
            assertThat(lanes.lane("io").tryAdmit()).isTrue();
            assertThat(lanes.lane("io").tryAdmit()).as("the second unit is rejected").isFalse();

            String text = new EdgeMetrics(new AggregatingMeter(), lanes).render();

            assertThat(text)
                    .contains("# TYPE tesseraql_lane_in_use gauge")
                    .contains("tesseraql_lane_in_use{lane=\"io\"} 1")
                    // A counter, not a gauge: rate() over a lifetime count needs the type.
                    .contains("# TYPE tesseraql_lane_rejected_total counter")
                    .contains("tesseraql_lane_rejected_total{lane=\"io\"} 1")
                    .as("no gate installed, no in-flight family")
                    .doesNotContain("tesseraql_http_in_flight");
        } finally {
            lanes.close();
        }
    }

    @Test
    void refusalsCountByCodeAndSumForTheAlert() {
        AggregatingMeter meter = new AggregatingMeter();
        EdgeMetrics metrics = new EdgeMetrics(meter, null);

        metrics.refused(new TqlErrorCode(TqlDomain.RATE, 4294));
        metrics.refused(new TqlErrorCode(TqlDomain.RATE, 4294));
        metrics.refused(new TqlErrorCode(TqlDomain.RATE, 4296));

        assertThat(EdgeMetrics.refusedTotal(meter)).isEqualTo(3);
        assertThat(PrometheusTextFormat.render(meter))
                .contains("# TYPE tesseraql_http_refused_total counter")
                .contains("tesseraql_http_refused_total{code=\"TQL-RATE-4294\"} 2")
                .contains("tesseraql_http_refused_total{code=\"TQL-RATE-4296\"} 1");
        assertThat(EdgeMetrics.refusedTotal(new AggregatingMeter()))
                .as("no refusal yet reads as zero, not as an absent family").isZero();
    }

    @Test
    void theGatewaysShareRendersOnceAHostWiresIt() {
        EdgeMetrics metrics = new EdgeMetrics(new AggregatingMeter(), null);
        assertThat(metrics.render()).doesNotContain("kind=\"forward\"");

        metrics.gatewayForwards(() -> 3, () -> 1);

        assertThat(metrics.render())
                .contains("# TYPE tesseraql_http_in_flight gauge")
                .contains("tesseraql_http_in_flight{kind=\"forward\"} 3")
                .contains("tesseraql_http_in_flight{kind=\"streamForward\"} 1");
    }
}
