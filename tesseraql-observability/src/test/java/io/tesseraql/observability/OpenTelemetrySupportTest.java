package io.tesseraql.observability;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

/**
 * What the shipped OTLP factory actually wires.
 *
 * <p>Both load-bearing lines of {@code otlp(...)} could be deleted with the whole module suite
 * green. {@code SpanIdentityTest} looks like it covers the first, but it installs
 * {@code SuppliedIdGenerator} on <em>its own</em> {@code SdkTracerProvider} — so it is red on the
 * mechanism and green on the factory's wiring, which is the level a deployment gets. The incident
 * behind the rule is real: the exported span id and the logged id were once unrelated values, and
 * nobody could pivot from a log line to a trace (docs/audit-hardening.md Decision 7).
 *
 * <p><b>No span is ended here, on purpose.</b> Ending one enqueues it on the live
 * {@code BatchSpanProcessor}, whose flush then goes to a collector that is not there — eight
 * seconds and five SEVERE connection failures for assertions that do not need it.
 * {@code toSpanData()} reads a live span perfectly well.
 *
 * <p><b>What this still does not cover, stated rather than implied.</b> Deleting only the meter
 * provider's {@code setResource} leaves this green, because the assertion reads the tracer
 * provider's copy of the same value. And deleting the whole span-export block leaves it green too:
 * the module has no test that OTLP export is installed at all, and getting one honestly needs an
 * injected exporter — a production seam this coverage does not justify.
 */
class OpenTelemetrySupportTest {

    private static final String ENDPOINT = "http://localhost:4317";

    /** A span started on the factory's own provider, without ending it. */
    private static SpanData started(OpenTelemetrySdk sdk, String traceId, String spanId) {
        SuppliedIdGenerator.supply(traceId, spanId);
        try {
            return ((ReadableSpan) sdk.getTracer("tesseraql.test")
                    .spanBuilder("tesseraql.route").startSpan()).toSpanData();
        } finally {
            SuppliedIdGenerator.clear();
        }
    }

    /**
     * The id generator is installed on the provider the factory builds, not merely available.
     * Delete {@code setIdGenerator} and the span carries an SDK-random id instead.
     */
    @Test
    void theFactoryInstallsTheIdGeneratorTheLogsShare() {
        try (OpenTelemetrySdk sdk = OpenTelemetrySupport.otlp(ENDPOINT, "test-app")) {
            SpanData span = started(sdk, "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");

            assertThat(span.getSpanId())
                    .as("the exported span carries the id the ring assigned, so a log line and a"
                            + " trace are the same value")
                    .isEqualTo("b7ad6b7169203331");
            assertThat(span.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        }
    }

    /** The service name reaches the span's resource; without {@code setResource} it is the
     * SDK's {@code unknown_service:java}. */
    @Test
    void theServiceNameReachesTheSpansResource() {
        try (OpenTelemetrySdk sdk = OpenTelemetrySupport.otlp(ENDPOINT, "test-app")) {
            SpanData span = started(sdk, "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203332");

            assertThat(span.getResource().getAttribute(stringKey("service.name")))
                    .isEqualTo("test-app");
        }
    }
}
