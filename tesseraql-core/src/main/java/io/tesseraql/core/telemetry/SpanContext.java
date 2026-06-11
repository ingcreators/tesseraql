package io.tesseraql.core.telemetry;

/**
 * The identity of a span for trace propagation (design ch. 25.4): the trace it belongs to and its
 * own span id. Propagated across route steps (and thread handoffs) so child spans can link to their
 * parent and the Operations UI can reconstruct the trace tree.
 *
 * @param traceId the trace the span belongs to
 * @param spanId  the span's own id
 */
public record SpanContext(String traceId, String spanId) {
}
