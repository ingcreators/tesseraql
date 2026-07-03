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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The default SQL statement timeout (roadmap Phase 45): a runaway query is cancelled by the
 * driver — bounded BY DEFAULT via the app-wide {@code tesseraql.sql.timeoutSeconds}, overridden
 * per binding, and disabled only by an explicit {@code timeoutSeconds: 0}.
 */
@Testcontainers
class SqlTimeoutIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aRunawayQueryIsCancelledByTheAppWideDefault() throws Exception {
        // The app default is 1s; the query sleeps 10s — the driver cancels well before that.
        long started = System.nanoTime();
        HttpResponse<String> response = get("/api/slow");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(elapsedMs).isLessThan(8_000);
    }

    @Test
    void anExplicitZeroOptsALongRunningStatementOut() throws Exception {
        // timeoutSeconds: 0 disables the guard; a 2s sleep outlives the 1s app default.
        HttpResponse<String> response = get("/api/patient");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("done");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-sql-timeout-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: timeout-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  sql:
                    timeoutSeconds: 1
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path slow = target.resolve("web/api/slow");
        Files.createDirectories(slow);
        Files.writeString(slow.resolve("get.yml"), """
                version: tesseraql/v1
                id: slow
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: slow.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(slow.resolve("slow.sql"), "select pg_sleep(10) as nap\n");

        Path patient = target.resolve("web/api/patient");
        Files.createDirectories(patient);
        Files.writeString(patient.resolve("get.yml"), """
                version: tesseraql/v1
                id: patient
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: patient.sql
                  mode: query
                  timeoutSeconds: 0
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(patient.resolve("patient.sql"),
                "select 'done' as answer from pg_sleep(2)\n");
        return target;
    }
}
