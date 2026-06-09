package io.tesseraql.core.telemetry;

/**
 * Minimal tracing abstraction (design ch. 25.4). The framework records custom spans through this
 * interface; an OpenTelemetry-backed implementation lives in tesseraql-observability, and a no-op
 * is used when observability is disabled.
 */
public interface Tracer {

    /** Starts a root span; the caller must {@link Span#end()} it (try-with-resources is supported). */
    Span start(String name);

    /**
     * Starts a span as a child of {@code parent} (a new trace when {@code parent} is null). Tracers
     * that do not track context fall back to a root span.
     */
    default Span start(String name, SpanContext parent) {
        return start(name);
    }
}
