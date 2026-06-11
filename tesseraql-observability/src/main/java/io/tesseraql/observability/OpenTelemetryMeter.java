package io.tesseraql.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.tesseraql.core.telemetry.Meter;
import java.util.Map;

/**
 * OpenTelemetry-backed {@link Meter} (design ch. 25.6), bridging the framework's metrics abstraction
 * to the OpenTelemetry Metrics API.
 */
public final class OpenTelemetryMeter implements Meter {

    private final io.opentelemetry.api.metrics.Meter meter;

    public OpenTelemetryMeter(OpenTelemetry openTelemetry) {
        this(openTelemetry.getMeter("io.tesseraql"));
    }

    public OpenTelemetryMeter(io.opentelemetry.api.metrics.Meter meter) {
        this.meter = meter;
    }

    @Override
    public Counter counter(String name) {
        LongCounter counter = meter.counterBuilder(name).build();
        return (delta, attributes) -> counter.add(delta, toAttributes(attributes));
    }

    private static io.opentelemetry.api.common.Attributes toAttributes(
            Map<String, String> attributes) {
        AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder();
        attributes.forEach(builder::put);
        return builder.build();
    }
}
