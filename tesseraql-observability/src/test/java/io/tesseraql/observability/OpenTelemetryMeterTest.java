package io.tesseraql.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.tesseraql.core.telemetry.Meter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTelemetryMeterTest {

    @Test
    void recordsCounterValues() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setMeterProvider(provider).build();
        OpenTelemetryMeter meter = new OpenTelemetryMeter(sdk);

        Meter.Counter counter = meter.counter("tesseraql.route.invocations");
        counter.increment(Map.of("routeId", "users.search"));
        counter.increment(Map.of("routeId", "users.search"));

        var metrics = reader.collectAllMetrics();
        assertThat(metrics).anySatisfy(
                metric -> assertThat(metric.getName()).isEqualTo("tesseraql.route.invocations"));
        long total = metrics.stream()
                .filter(m -> m.getName().equals("tesseraql.route.invocations"))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .mapToLong(point -> point.getValue())
                .sum();
        assertThat(total).isEqualTo(2);
    }
}
