package io.tesseraql.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AggregatingMeterTest {

    @Test
    void countersAccumulatePerLabelSet() {
        AggregatingMeter meter = new AggregatingMeter();
        meter.counter("tesseraql.route.invocations").increment(Map.of("routeId", "a"));
        meter.counter("tesseraql.route.invocations").increment(Map.of("routeId", "a"));
        meter.counter("tesseraql.route.invocations").increment(Map.of("routeId", "b"));

        var samples = meter.counterSnapshot().get("tesseraql.route.invocations");
        assertThat(samples).hasSize(2);
        assertThat(samples.stream()
                .filter(sample -> sample.attributes().get("routeId").equals("a"))
                .findFirst().orElseThrow().value()).isEqualTo(2);
    }

    @Test
    void histogramsBucketCumulativelyAndTrackCountAndSum() {
        AggregatingMeter meter = new AggregatingMeter();
        var histogram = meter.histogram("tesseraql.route.duration");
        histogram.record(3, Map.of("routeId", "a")); // <= 5ms bucket
        histogram.record(80, Map.of("routeId", "a")); // <= 100ms bucket
        histogram.record(99_999, Map.of("routeId", "a")); // above all bounds -> +Inf only

        var sample = meter.histogramSnapshot().get("tesseraql.route.duration").get(0);
        assertThat(sample.count()).isEqualTo(3);
        assertThat(sample.sumMillis()).isEqualTo(3 + 80 + 99_999);
        long[] cumulative = sample.bucketCounts();
        // Cumulative: the 5ms bound holds 1, everything from 100ms on holds 2; +Inf (count) 3.
        assertThat(cumulative[0]).isEqualTo(1);
        assertThat(cumulative[4]).isEqualTo(2);
        assertThat(cumulative[cumulative.length - 1]).isEqualTo(2);
    }

    @Test
    void prometheusTextFormatRendersCountersAndHistogramsInSeconds() {
        AggregatingMeter meter = new AggregatingMeter();
        meter.counter("tesseraql.route.invocations")
                .increment(Map.of("routeId", "users.search", "method", "GET"));
        meter.histogram("tesseraql.route.duration").record(80,
                Map.of("routeId", "users.search", "method", "GET", "outcome", "2xx"));

        String exposition = PrometheusTextFormat.render(meter);

        assertThat(exposition)
                .contains("# TYPE tesseraql_route_invocations_total counter")
                .contains("tesseraql_route_invocations_total{method=\"GET\","
                        + "routeId=\"users.search\"} 1")
                .contains("# TYPE tesseraql_route_duration_seconds histogram")
                .contains("le=\"0.1\"")
                .contains("le=\"+Inf\"")
                .contains("tesseraql_route_duration_seconds_sum")
                .contains("tesseraql_route_duration_seconds_count");
        // The 80ms observation lands in the 0.1s bucket cumulatively.
        assertThat(exposition).containsPattern("le=\"0.1\"\\} 1");
    }
}
