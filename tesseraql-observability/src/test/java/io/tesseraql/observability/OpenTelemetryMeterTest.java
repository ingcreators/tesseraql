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

    /**
     * One OTel instrument per name (docs/audit-low-leads.md slice 16, F109): the route
     * telemetry asks for its counter and histogram on every request, and each ask built a new
     * SDK instrument. The guard counts the builder calls on a spy {@code Meter} — comparing
     * {@code counter("x") == counter("x")} would be wrong, a fresh lambda is handed back each
     * time by design — and the recorded values still land on the one instrument.
     */
    @Test
    void anInstrumentIsBuiltOncePerName() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        CountingMeter spy = new CountingMeter(provider.get("io.tesseraql"));
        OpenTelemetryMeter meter = new OpenTelemetryMeter(spy);

        meter.counter("tesseraql.route.invocations").increment(Map.of("routeId", "a"));
        meter.counter("tesseraql.route.invocations").increment(Map.of("routeId", "b"));
        meter.counter("tesseraql.route.errors").increment(Map.of("routeId", "a"));
        meter.histogram("tesseraql.route.duration").record(5, Map.of("routeId", "a"));
        meter.histogram("tesseraql.route.duration").record(7, Map.of("routeId", "a"));

        assertThat(spy.counterBuilds).as("counter builders by name")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "tesseraql.route.invocations", 1, "tesseraql.route.errors", 1));
        assertThat(spy.histogramBuilds).as("histogram builders by name")
                .containsExactly(Map.entry("tesseraql.route.duration", 1));
        long invocations = reader.collectAllMetrics().stream()
                .filter(m -> m.getName().equals("tesseraql.route.invocations"))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .mapToLong(point -> point.getValue())
                .sum();
        assertThat(invocations).isEqualTo(2);
    }

    /** A meter that counts its builder calls and delegates everything to the SDK's. */
    private static final class CountingMeter implements io.opentelemetry.api.metrics.Meter {
        private final io.opentelemetry.api.metrics.Meter delegate;
        final Map<String, Integer> counterBuilds = new java.util.HashMap<>();
        final Map<String, Integer> histogramBuilds = new java.util.HashMap<>();

        CountingMeter(io.opentelemetry.api.metrics.Meter delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.opentelemetry.api.metrics.LongCounterBuilder counterBuilder(String name) {
            counterBuilds.merge(name, 1, Integer::sum);
            return delegate.counterBuilder(name);
        }

        @Override
        public io.opentelemetry.api.metrics.LongUpDownCounterBuilder upDownCounterBuilder(
                String name) {
            return delegate.upDownCounterBuilder(name);
        }

        @Override
        public io.opentelemetry.api.metrics.DoubleHistogramBuilder histogramBuilder(String name) {
            histogramBuilds.merge(name, 1, Integer::sum);
            return delegate.histogramBuilder(name);
        }

        @Override
        public io.opentelemetry.api.metrics.DoubleGaugeBuilder gaugeBuilder(String name) {
            return delegate.gaugeBuilder(name);
        }
    }
}
