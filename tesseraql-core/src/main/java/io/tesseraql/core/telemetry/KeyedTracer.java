package io.tesseraql.core.telemetry;

/**
 * A {@link Tracer} that can adopt an externally supplied span identity (design ch. 25.4). Used by
 * {@link CompositeTracer} so a secondary tracer (for example the OpenTelemetry exporter) keys its
 * spans by the primary tracer's span id, letting it rebuild the same parent/child hierarchy from the
 * propagated {@link SpanContext}.
 */
public interface KeyedTracer extends Tracer {

    /**
     * Starts a span that links to {@code parent} (by the parent's span id) and registers itself under
     * {@code identity}'s span id, so later children naming this id as their parent nest correctly.
     */
    Span start(String name, SpanContext parent, SpanContext identity);
}
