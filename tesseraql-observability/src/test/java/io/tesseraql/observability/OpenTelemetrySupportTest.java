package io.tesseraql.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.tesseraql.core.telemetry.Tracer;
import org.junit.jupiter.api.Test;

class OpenTelemetrySupportTest {

    @Test
    void buildsAnOtlpSdkThatRecordsSpansWithoutError() {
        // No collector is required to build/use the SDK; export happens asynchronously.
        assertThatCode(() -> {
            try (OpenTelemetrySdk sdk = OpenTelemetrySupport.otlp("http://localhost:4317",
                    "test-app")) {
                Tracer tracer = new OpenTelemetryTracer(sdk);
                tracer.start("tesseraql.route").attribute("routeId", "users.search").end();
                assertThat(sdk).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
