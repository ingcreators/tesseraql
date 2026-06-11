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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end test that batch execution exports a nested OpenTelemetry tree (design ch. 25.4):
 * {@code tesseraql.job} -&gt; {@code tesseraql.job.step} -&gt; {@code tesseraql.sql.execute}.
 */
@Testcontainers
class BatchOtelTraceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

    static TesseraqlRuntime runtime;
    static OpenTelemetrySdk sdk;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER)).build())
                .build();
        CompositeTracer tracer = new CompositeTracer(new RingTracer(50), new OpenTelemetryTracer(sdk));

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
    void batchRunExportsJobStepSqlTree() {
        runtime.runJob("user.dailyMaintenance", Map.of());

        SpanData job = span("tesseraql.job").orElseThrow();
        SpanData step = span("tesseraql.job.step").orElseThrow();
        SpanData sql = span("tesseraql.sql.execute").orElseThrow();

        assertThat(job.getParentSpanContext().isValid()).isFalse();
        assertThat(step.getParentSpanContext().getSpanId()).isEqualTo(job.getSpanId());
        assertThat(sql.getParentSpanContext().getSpanId()).isEqualTo(step.getSpanId());
        assertThat(sql.getTraceId()).isEqualTo(job.getTraceId());
    }

    private static Optional<SpanData> span(String name) {
        return EXPORTER.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name)).findFirst();
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32) not null, created_at timestamp default now())");
            statement.execute("insert into users (name, status) values ('pending-user','PENDING')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-batch-otel-it");
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
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
