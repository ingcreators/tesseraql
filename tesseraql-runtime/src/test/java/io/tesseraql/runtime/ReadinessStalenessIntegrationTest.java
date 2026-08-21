package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A readiness roll-up that cannot be refreshed stops being a readiness answer
 * (docs/http-threading.md decision 3).
 *
 * <p>Answering from a memo is what takes readiness off the worker pool, and it introduces exactly
 * one new way to be wrong: keeping on serving a cheerful answer computed before the trouble
 * started. The failure that makes it concrete is a database that accepts connections and never
 * replies — the probe hangs for {@code connectionTimeout}, thirty seconds by default — and
 * answering {@code UP} for thirty seconds would be worse than the route this replaced, which at
 * least hung and let the orchestrator's own timeout fire.
 *
 * <p>So this holds the probe open and asserts both halves: every poll is answered <em>promptly</em>
 * throughout, and the answer becomes {@code DOWN} once the memo is too old to be a claim about
 * now.
 */
@Testcontainers
class ReadinessStalenessIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Short so the test is short; the rule is a multiple of it, not a number of its own. */
    private static final String TTL = "200ms";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        delete(appHome);
    }

    @Test
    void aRollUpThatCannotBeRefreshedStopsBeingAReadinessAnswer() throws Exception {
        assertThat(get("/_tesseraql/health/ready").statusCode()).isEqualTo(200);

        CountDownLatch held = new CountDownLatch(1);
        runtime.opsDashboard().datasourceProbe(() -> {
            try {
                held.await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return Map.of("main", Boolean.TRUE);
        });
        try {
            int status = 200;
            long deadline = System.currentTimeMillis() + 5_000;
            while (status == 200 && System.currentTimeMillis() < deadline) {
                long startedAt = System.currentTimeMillis();
                status = get("/_tesseraql/health/ready").statusCode();
                long elapsedMs = System.currentTimeMillis() - startedAt;
                // The refresh is hanging on the probe. No poll waits for it.
                assertThat(elapsedMs).isLessThan(500);
                Thread.sleep(50);
            }

            assertThat(status).isEqualTo(503);
        } finally {
            held.countDown();
        }
    }

    private static HttpResponse<String> get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + path)).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-readiness-staleness-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: readiness-it
                  diagnostics:
                    readinessTtl: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(TTL, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        return target;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void delete(Path target) throws IOException {
        if (target == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(target)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
