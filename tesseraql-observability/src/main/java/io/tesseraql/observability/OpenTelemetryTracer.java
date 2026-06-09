package io.tesseraql.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.Tracer;

/**
 * OpenTelemetry-backed {@link Tracer} (design ch. 25.2, 25.4). Bridges the framework's telemetry
 * abstraction to the OpenTelemetry API so spans are exported via whatever SDK/exporter the host
 * configures (camel-opentelemetry2, OTLP, etc.).
 */
public final class OpenTelemetryTracer implements Tracer {

    private final io.opentelemetry.api.trace.Tracer tracer;

    public OpenTelemetryTracer(OpenTelemetry openTelemetry) {
        this(openTelemetry.getTracer("io.tesseraql"));
    }

    public OpenTelemetryTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Span start(String name) {
        return new OtelSpan(tracer.spanBuilder(name).startSpan());
    }

    private static final class OtelSpan implements Span {
        private final io.opentelemetry.api.trace.Span span;

        OtelSpan(io.opentelemetry.api.trace.Span span) {
            this.span = span;
        }

        @Override
        public Span attribute(String key, Object value) {
            span.setAttribute(AttributeKey.stringKey(key), value == null ? "" : String.valueOf(value));
            return this;
        }

        @Override
        public void recordError(Throwable error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getMessage() == null ? "error" : error.getMessage());
        }

        @Override
        public void end() {
            span.end();
        }
    }
}
