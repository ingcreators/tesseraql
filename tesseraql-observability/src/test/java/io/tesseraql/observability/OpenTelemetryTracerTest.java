package io.tesseraql.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.tesseraql.core.telemetry.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenTelemetryTracerTest {

    @Test
    void emitsSpanWithAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        OpenTelemetryTracer tracer = new OpenTelemetryTracer(sdk);

        try (Span span = tracer.start("tesseraql.sql.execute")) {
            span.attribute("sqlId", "web/api/users/search.sql").attribute("rowCount", 3);
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("tesseraql.sql.execute");
        assertThat(span.getAttributes().asMap().toString())
                .contains("sqlId").contains("rowCount").contains("3");
    }

    @Test
    void recordsErrorStatus() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
                .build();
        OpenTelemetryTracer tracer = new OpenTelemetryTracer(sdk);

        Span span = tracer.start("tesseraql.sql.execute");
        span.recordError(new IllegalStateException("boom"));
        span.end();

        assertThat(exporter.getFinishedSpanItems().get(0).getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }
}
