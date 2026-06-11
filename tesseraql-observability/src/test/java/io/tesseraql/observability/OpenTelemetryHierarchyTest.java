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

class OpenTelemetryHierarchyTest {

    @Test
    void childSpanNestsUnderParentInExportedTelemetry() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();

        // Mirror the runtime: an in-process ring assigns identity, OpenTelemetry mirrors the tree.
        CompositeTracer composite = new CompositeTracer(new RingTracer(8),
                new OpenTelemetryTracer(sdk));

        Span route = composite.start("tesseraql.route");
        Span sql = composite.start("tesseraql.sql.execute", route.context());
        sql.end();
        route.end();

        SpanData routeData = find(exporter, "tesseraql.route");
        SpanData sqlData = find(exporter, "tesseraql.sql.execute");
        assertThat(routeData.getParentSpanContext().isValid()).isFalse();
        assertThat(sqlData.getParentSpanContext().getSpanId()).isEqualTo(routeData.getSpanId());
        assertThat(sqlData.getTraceId()).isEqualTo(routeData.getTraceId());
    }

    private static SpanData find(InMemorySpanExporter exporter, String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No exported span named " + name));
    }
}
