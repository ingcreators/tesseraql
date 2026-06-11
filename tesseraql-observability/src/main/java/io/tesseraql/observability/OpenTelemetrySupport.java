package io.tesseraql.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;

/**
 * Builds an OpenTelemetry SDK that exports spans and metrics over OTLP/gRPC (design ch. 25.7). The
 * returned {@link OpenTelemetrySdk} is {@link AutoCloseable}; close it to flush and shut down the
 * exporters. Wrap it with {@link OpenTelemetryTracer}/{@link OpenTelemetryMeter} to emit telemetry.
 */
public final class OpenTelemetrySupport {

    private OpenTelemetrySupport() {
    }

    /** Creates an OTLP-exporting SDK targeting {@code endpoint} (e.g. {@code http://localhost:4317}). */
    public static OpenTelemetrySdk otlp(String endpoint, String serviceName) {
        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.builder().put("service.name", serviceName).build()));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()).build())
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(
                        OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build())
                        .setInterval(Duration.ofSeconds(30)).build())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();
    }
}
