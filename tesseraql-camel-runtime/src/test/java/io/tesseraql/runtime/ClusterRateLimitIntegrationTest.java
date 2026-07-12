package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.rate.JdbcRateLeaseStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Cluster-scoped rate limits end to end (docs/deployment.md, "Cluster rate limits"): the
 * lease ledger's claim semantics on a real database, and two runtimes sharing one PostgreSQL
 * enforcing a single budget — the property that a per-node limiter cannot have.
 */
@Testcontainers
class ClusterRateLimitIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime nodeA;
    static TesseraqlRuntime nodeB;
    static Path homeA;
    static Path homeB;

    @BeforeAll
    static void start() throws Exception {
        homeA = prepareAppHome();
        homeB = prepareAppHome();
        nodeA = TesseraqlRuntime.start(homeA, freePort());
        nodeB = TesseraqlRuntime.start(homeB, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : new TesseraqlRuntime[]{nodeA, nodeB}) {
            if (runtime != null) {
                runtime.close();
            }
        }
        for (Path home : new Path[]{homeA, homeB}) {
            if (home != null) {
                try (var files = Files.walk(home)) {
                    files.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> path.toFile().delete());
                }
            }
        }
    }

    /** The ledger's atomic claim semantics: full grant, partial remainder, exhaustion. */
    @Test
    void theLedgerGrantsAtomicallyUpToTheWindowBudget() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcRateLeaseStore store = new JdbcRateLeaseStore(dataSource);
        store.ensureSchema();

        // Full grant, then the remainder, then exhaustion — one window, one scope.
        assertThat(store.claim("t|route", 1_000, 6, 10)).isEqualTo(6);
        assertThat(store.claim("t|route", 1_000, 6, 10)).isEqualTo(4);
        assertThat(store.claim("t|route", 1_000, 6, 10)).isZero();
        // A new window and a different scope each carry their own budget.
        assertThat(store.claim("t|route", 1_001, 6, 10)).isEqualTo(6);
        assertThat(store.claim("t|other", 1_000, 6, 10)).isEqualTo(6);
    }

    /**
     * The cluster property: a burst fired at BOTH nodes together stays within the shared
     * budget. With per-node scope the same volley would pass at least twice the declared
     * rate; here the total stays bounded by budget × the (at most two) windows it straddles.
     */
    @Test
    void twoNodesEnforceOneBudget() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            for (TesseraqlRuntime node : new TesseraqlRuntime[]{nodeA, nodeB}) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + node.port() + "/limited")).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    allowed++;
                } else {
                    assertThat(response.statusCode()).isEqualTo(429);
                }
            }
        }
        // 40 shots against a cluster budget of 5/s: even straddling two second-windows the
        // total stays far below the 40 a wide-open route (or ~2×5×windows a per-node limiter)
        // would allow. The lower bound proves the leased budget actually serves requests.
        assertThat(allowed).isBetween(5, 14);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-cluster-rate-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: cluster-rate-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path limited = target.resolve("web/limited");
        Files.createDirectories(limited);
        Files.writeString(limited.resolve("limited.sql"), "select 1 as ok\n");
        Files.writeString(limited.resolve("get.yml"), """
                version: tesseraql/v1
                id: limited.get
                kind: route
                recipe: query-json
                security:
                  auth: public
                policy:
                  rateLimit:
                    requestsPerSecond: 5
                    scope: cluster
                sql:
                  file: limited.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """);
        return target;
    }
}
