package io.tesseraql.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.tesseraql.core.telemetry.Meter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenTelemetry-backed {@link Meter} (design ch. 25.6), bridging the framework's metrics abstraction
 * to the OpenTelemetry Metrics API.
 *
 * <p>One OTel instrument per name, memoized the way {@code AggregatingMeter} memoizes its own:
 * the route telemetry asks for its counter and histogram on every request, and building an SDK
 * instrument per call cost a descriptor registration each time (docs/audit-low-leads.md slice
 * 16, F109) — the SDK dedupes identical descriptors, so nothing was wrong on the wire, only
 * the work.
 */
public final class OpenTelemetryMeter implements Meter {

    private final io.opentelemetry.api.metrics.Meter meter;
    private final Map<String, LongCounter> counters = new ConcurrentHashMap<>();
    private final Map<String, LongHistogram> histograms = new ConcurrentHashMap<>();

    public OpenTelemetryMeter(OpenTelemetry openTelemetry) {
        this(openTelemetry.getMeter("io.tesseraql"));
    }

    public OpenTelemetryMeter(io.opentelemetry.api.metrics.Meter meter) {
        this.meter = meter;
    }

    @Override
    public Counter counter(String name) {
        LongCounter counter = counters.computeIfAbsent(name,
                key -> meter.counterBuilder(key).build());
        return (delta, attributes) -> counter.add(delta, toAttributes(attributes));
    }

    @Override
    public Histogram histogram(String name) {
        LongHistogram histogram = histograms.computeIfAbsent(name,
                key -> meter.histogramBuilder(key).ofLongs().setUnit("ms").build());
        return (value, attributes) -> histogram.record(value, toAttributes(attributes));
    }

    private static io.opentelemetry.api.common.Attributes toAttributes(
            Map<String, String> attributes) {
        AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder();
        attributes.forEach(builder::put);
        return builder.build();
    }
}
