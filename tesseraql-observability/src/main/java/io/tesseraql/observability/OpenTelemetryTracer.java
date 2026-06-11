package io.tesseraql.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.tesseraql.core.telemetry.KeyedTracer;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.SpanContext;
import io.tesseraql.core.telemetry.Tracer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenTelemetry-backed {@link Tracer} (design ch. 25.2, 25.4). Bridges the framework's telemetry
 * abstraction to the OpenTelemetry API so spans are exported via whatever SDK/exporter the host
 * configures (camel-opentelemetry2, OTLP, etc.).
 *
 * <p>As a {@link KeyedTracer} it can adopt the in-process tracer's span id as its key, recording the
 * OpenTelemetry {@link Context} for each live span so child spans link to their parent natively —
 * preserving the trace tree in exported telemetry across thread handoffs.
 */
public final class OpenTelemetryTracer implements KeyedTracer {

    private final io.opentelemetry.api.trace.Tracer tracer;
    private final ConcurrentHashMap<String, Context> liveContexts = new ConcurrentHashMap<>();

    public OpenTelemetryTracer(OpenTelemetry openTelemetry) {
        this(openTelemetry.getTracer("io.tesseraql"));
    }

    public OpenTelemetryTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Span start(String name) {
        return start(name, null, null);
    }

    @Override
    public Span start(String name, SpanContext parent) {
        return start(name, parent, null);
    }

    @Override
    public Span start(String name, SpanContext parent, SpanContext identity) {
        Context parentContext = parent != null ? liveContexts.get(parent.spanId()) : null;
        if (parentContext == null) {
            parentContext = Context.root();
        }
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder(name)
                .setParent(parentContext).startSpan();
        // The key future children will reference: the shared identity if supplied, else our own id.
        String key = identity != null ? identity.spanId() : span.getSpanContext().getSpanId();
        liveContexts.put(key, span.storeInContext(parentContext));
        return new OtelSpan(span, key, liveContexts);
    }

    private static final class OtelSpan implements Span {
        private final io.opentelemetry.api.trace.Span span;
        private final String key;
        private final ConcurrentHashMap<String, Context> liveContexts;

        OtelSpan(io.opentelemetry.api.trace.Span span, String key,
                ConcurrentHashMap<String, Context> liveContexts) {
            this.span = span;
            this.key = key;
            this.liveContexts = liveContexts;
        }

        @Override
        public Span attribute(String key, Object value) {
            span.setAttribute(AttributeKey.stringKey(key),
                    value == null ? "" : String.valueOf(value));
            return this;
        }

        @Override
        public void recordError(Throwable error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR,
                    error.getMessage() == null ? "error" : error.getMessage());
        }

        @Override
        public void end() {
            liveContexts.remove(key);
            span.end();
        }
    }
}
