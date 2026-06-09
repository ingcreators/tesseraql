package io.tesseraql.core.telemetry;

/**
 * Minimal tracing abstraction (design ch. 25.4). The framework records custom spans through this
 * interface; an OpenTelemetry-backed implementation lives in tesseraql-observability, and a no-op
 * is used when observability is disabled.
 */
public interface Tracer {

    /** Starts a span; the caller must {@link Span#end()} it (try-with-resources is supported). */
    Span start(String name);
}
