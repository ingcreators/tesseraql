package io.tesseraql.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.tesseraql.core.telemetry.CompositeTracer;
import io.tesseraql.core.telemetry.RingTracer;
import io.tesseraql.core.telemetry.Span;
import org.junit.jupiter.api.Test;

/**
 * The id in the log line and the id on the exported span are one value
 * (docs/audit-hardening.md Decision 7).
 *
 * <p>They were unrelated. {@code CompositeTracer} takes its identity from the ring, and that id
 * reaches every structured log line through the MDC and the console's trace pages; the exported
 * span carried whatever the SDK generated. Nobody could pivot from a log line to a trace, which
 * made the shipped OTLP feature close to unusable.
 */
class SpanIdentityTest {

    private static OpenTelemetrySdk sdk(InMemorySpanExporter exporter) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setIdGenerator(new SuppliedIdGenerator())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
    }

    /**
     * The assertion the whole slice exists for.
     *
     * <p>Reshaping the ring's id format alone would not have satisfied it: the tracer was already
     * handed the ring's identity and used it only as a map key, so the exported span kept
     * SDK-generated ids either way.
     */
    @Test
    void theExportedSpanCarriesTheIdTheRingAssigned() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        RingTracer ring = new RingTracer(8);
        CompositeTracer composite = new CompositeTracer(ring, new OpenTelemetryTracer(
                sdk(exporter)));

        Span route = composite.start("tesseraql.route");
        String loggedTraceId = route.context().traceId();
        String loggedSpanId = route.context().spanId();
        route.end();

        SpanData exported = exporter.getFinishedSpanItems().get(0);
        assertThat(exported.getTraceId()).isEqualTo(loggedTraceId);
        assertThat(exported.getSpanId()).isEqualTo(loggedSpanId);
    }

    /** And it holds down a tree, not only at the root. */
    @Test
    void aChildSpanKeepsTheSharedIdentityToo() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        CompositeTracer composite = new CompositeTracer(new RingTracer(8),
                new OpenTelemetryTracer(sdk(exporter)));

        Span route = composite.start("tesseraql.route");
        Span sql = composite.start("tesseraql.sql.execute", route.context());
        String childTraceId = sql.context().traceId();
        String childSpanId = sql.context().spanId();
        sql.end();
        route.end();

        SpanData exported = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("tesseraql.sql.execute"))
                .findFirst().orElseThrow();
        assertThat(exported.getTraceId()).isEqualTo(childTraceId);
        assertThat(exported.getSpanId()).isEqualTo(childSpanId);
        // The tree still nests: sharing the identity did not cost the parent link.
        assertThat(exported.getParentSpanContext().getSpanId())
                .isEqualTo(route.context().spanId());
    }

    /**
     * The ring's ids are W3C-shaped, which is what makes them installable at all.
     *
     * <p>They were {@code Long.toHexString(counter)} — fine while nothing outside the process read
     * them, and rejected by the SDK as soon as they became the exported span's own ids.
     */
    @Test
    void theRingMintsWireShapedIds() {
        RingTracer ring = new RingTracer(4);
        Span span = ring.start("probe");

        assertThat(span.context().traceId()).hasSize(32).matches("[0-9a-f]{32}")
                .isNotEqualTo("0".repeat(32));
        assertThat(span.context().spanId()).hasSize(16).matches("[0-9a-f]{16}")
                .isNotEqualTo("0".repeat(16));
        span.end();
    }

    /** Two roots do not share a trace id, which a counter would have made easy to get wrong. */
    @Test
    void separateRootsGetSeparateTraceIds() {
        RingTracer ring = new RingTracer(4);
        Span first = ring.start("one");
        Span second = ring.start("two");

        assertThat(first.context().traceId()).isNotEqualTo(second.context().traceId());
        assertThat(first.context().spanId()).isNotEqualTo(second.context().spanId());
        first.end();
        second.end();
    }

    /**
     * A span the SDK starts without a TesseraQL identity still gets valid ids.
     *
     * <p>The handoff is a thread local, so anything that bypasses the tracer — the SDK's own
     * internal spans, a library instrumenting itself — has to fall through to random generation
     * rather than reuse whatever the last span left behind.
     */
    @Test
    void aSpanStartedWithoutAnIdentityFallsThroughToRandomIds() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = sdk(exporter);
        CompositeTracer composite = new CompositeTracer(new RingTracer(8),
                new OpenTelemetryTracer(sdk));

        Span identified = composite.start("tesseraql.route");
        identified.end();

        sdk.getTracer("bypass").spanBuilder("library.span").startSpan().end();

        SpanData bypassed = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("library.span"))
                .findFirst().orElseThrow();
        assertThat(bypassed.getSpanId()).isNotEqualTo(identified.context().spanId());
        assertThat(bypassed.getTraceId()).isNotEqualTo(identified.context().traceId());
    }
}
