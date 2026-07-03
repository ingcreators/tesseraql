package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Truthful health (roadmap Phase 45): {@code /health/live} is pure liveness and stays 200 no
 * matter what; {@code /health/ready} (and the bare {@code /health}) probe every datasource live
 * and answer {@code 503 DOWN} once the database goes away. A dedicated container so stopping
 * the database cannot disturb the shared fixtures of the other integration tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthProbeIntegrationTest {

    // Managed by hand (not @Container) because the test itself stops it mid-flight.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        POSTGRES.stop();
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    @Order(1)
    void liveAndReadyReportUpWhileTheDatabaseIsHealthy() throws Exception {
        assertThat(get("/_tesseraql/health/live").body()).contains("UP");
        HttpResponse<String> ready = get("/_tesseraql/health/ready");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(ready.body()).get("status").asText()).isEqualTo("UP");
        // The bare /health serves the same readiness roll-up.
        assertThat(get("/_tesseraql/health").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void readinessDegradesToDownWhenTheDatabaseStopsWhileLivenessStaysUp() throws Exception {
        POSTGRES.stop();

        HttpResponse<String> ready = get("/_tesseraql/health/ready");
        assertThat(ready.statusCode()).isEqualTo(503);
        assertThat(MAPPER.readTree(ready.body()).get("status").asText()).isEqualTo("DOWN");
        assertThat(get("/_tesseraql/health").statusCode()).isEqualTo(503);

        // Liveness never touches a dependency: the process still answers, so an orchestrator
        // does not restart a pod for a database outage it cannot fix.
        HttpResponse<String> live = get("/_tesseraql/health/live");
        assertThat(live.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(live.body()).get("status").asText()).isEqualTo("UP");
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .timeout(Duration.ofSeconds(30))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "scaffold-demo-app").toAbsolutePath()
                .normalize();
        Path target = Files.createTempDirectory("tesseraql-health-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  datasources:
                    main:
                      # A short borrow timeout so the readiness probe detects the outage fast
                      # (roadmap Phase 45; the default 30s would stall this test, not fail it).
                      connectionTimeoutMillis: 2000
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        return target;
    }
}
