package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.tesseraql.core.telemetry.CompositeTracer;
import io.tesseraql.core.telemetry.NoopMeter;
import io.tesseraql.core.telemetry.RingTracer;
import io.tesseraql.observability.OpenTelemetryTracer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end test that the OpenTelemetry trace tree survives a virtual-thread lane handoff
 * (design ch. 24, 25.4): a lane-bound route exports a {@code tesseraql.route} span with the
 * {@code tesseraql.sql.execute} span nested under it, even though SQL runs on the lane thread.
 */
@Testcontainers
class LaneOtelTraceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

    static TesseraqlRuntime runtime;
    static OpenTelemetrySdk sdk;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER)).build())
                .build();
        CompositeTracer tracer = new CompositeTracer(new RingTracer(50),
                new OpenTelemetryTracer(sdk));

        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort(), tracer, NoopMeter.INSTANCE);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (sdk != null) {
            sdk.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void exportedTraceTreeSurvivesLaneHandoff() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((span("tesseraql.route").isEmpty() || span("tesseraql.sql.execute").isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        SpanData route = span("tesseraql.route").orElseThrow();
        SpanData sql = span("tesseraql.sql.execute").orElseThrow();
        assertThat(route.getParentSpanContext().isValid()).isFalse();
        assertThat(sql.getParentSpanContext().getSpanId()).isEqualTo(route.getSpanId());
        assertThat(sql.getTraceId()).isEqualTo(route.getTraceId());
    }

    private static Optional<SpanData> span(String name) {
        return EXPORTER.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name)).findFirst();
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-lane-otel-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                threading:
                  lanes:
                    io:
                      type: virtual
                      maxConcurrency: 4
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path pingDir = target.resolve("web/api/ping");
        Files.createDirectories(pingDir);
        Files.writeString(pingDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                policy:
                  lane: io
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        Files.writeString(pingDir.resolve("ping.sql"), "select 1 as ok\n");
        return target;
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
