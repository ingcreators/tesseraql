package io.tesseraql.observability;

import io.opentelemetry.sdk.trace.IdGenerator;

/**
 * Makes the exported span carry the id the log line carries
 * (docs/audit-hardening.md Decision 7).
 *
 * <p>The two were unrelated values. {@code CompositeTracer} takes its identity from the first
 * delegate — the ring — and every structured log line, the MDC and the console's trace pages carry
 * that id; the exported span carried whatever the SDK generated. Nobody could pivot from a log line
 * to a trace, which made the shipped OTLP feature close to unusable.
 *
 * <p><b>Reshaping the ring's ids alone does not fix it, and that was the trap.</b>
 * {@code OpenTelemetryTracer} was already handed the ring's identity, but used it only as a key
 * into its live-context map: the span itself came from {@code spanBuilder(name).startSpan()} with
 * SDK-generated ids, so a W3C-shaped ring id would still not have equalled the exported one. The
 * ids have to be installed where the SDK mints them, which is here.
 *
 * <p>The handoff is a thread local because {@code startSpan()} is synchronous on the calling
 * thread: the tracer sets the identity, calls the builder, and clears it in a finally. Anything the
 * SDK starts without that handoff — its own internal spans, or any caller that bypasses the
 * TesseraQL tracer — falls through to the delegate and gets ordinary random ids rather than a
 * collision.
 *
 * <p>The alternative considered and rejected: exporting the ring id as a span attribute. That lets
 * a reader go from a trace to the logs, and the direction that matters in an incident is from a log
 * line to the trace.
 */
final class SuppliedIdGenerator implements IdGenerator {

    private static final ThreadLocal<String[]> SUPPLIED = new ThreadLocal<>();

    private final IdGenerator delegate = IdGenerator.random();

    /** Supplies the ids the next span started on this thread will carry. */
    static void supply(String traceId, String spanId) {
        SUPPLIED.set(new String[]{traceId, spanId});
    }

    /** Clears the handoff; always called in a finally, so one span cannot leak into the next. */
    static void clear() {
        SUPPLIED.remove();
    }

    @Override
    public String generateSpanId() {
        String[] supplied = SUPPLIED.get();
        return supplied == null || supplied[1] == null
                ? delegate.generateSpanId()
                : supplied[1];
    }

    @Override
    public String generateTraceId() {
        String[] supplied = SUPPLIED.get();
        return supplied == null || supplied[0] == null
                ? delegate.generateTraceId()
                : supplied[0];
    }
}
