package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.tesseraql.core.rate.RateBudget;
import io.tesseraql.operations.rate.JdbcRateLeaseStore;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        nodeA = TesseraqlRuntime.start(homeA, 0);
        nodeB = TesseraqlRuntime.start(homeB, 0);
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

    /**
     * The claim path is bounded on both halves. The pool bounds the borrow; nothing bounded the
     * statements, and {@code tql_rate_lease} is one row per (scope, window) that every node
     * UPDATEs every second — so a lock held by a migration or a backup blocked a claim with no
     * bound at all, and PostgreSQL sets neither {@code lock_timeout} nor {@code statement_timeout}
     * by default.
     *
     * <p><strong>Red before green:</strong> the same case with {@code sqlTimeoutSeconds} left
     * unset does not fail, it hangs — the claim waits out the pool's {@code connectionTimeout}
     * behind a row lock this test never releases until the assertion has run.
     */
    @Test
    void aHeldRowLockDoesNotHoldAClaimPastTheStatementTimeout() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcRateLeaseStore store = new JdbcRateLeaseStore(dataSource).sqlTimeoutSeconds(2);
        store.ensureSchema();
        long window = 9_000;
        assertThat(store.claim("lock|route", window, 1, 10)).isEqualTo(1);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "select granted from tql_rate_lease where scope_key = ? and window_start = ?"
                            + " for update")) {
                lock.setString(1, "lock|route");
                lock.setLong(2, window);
                lock.executeQuery().close();
            }
            long start = System.nanoTime();
            Throwable refusal = catchThrowable(() -> store.claim("lock|route", window, 1, 10));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(refusal)
                    .as("the claim gives up rather than waiting out the pool")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sqlStates(refusal))
                    .as("and it gives up because the STATEMENT bound fired — 57014 is the only"
                            + " way this row can be given up on, so the case cannot pass by"
                            + " failing quickly for some unrelated reason")
                    .contains("57014");
            assertThat(elapsedMs)
                    .as("it waited for that bound rather than failing instantly")
                    .isBetween(1_000L, 15_000L);
            holder.rollback();
        }
    }

    /**
     * F119, composed rather than inferred: a stalled ledger on ONE cluster-scoped route must not
     * cost the node its whole in-flight budget and make an <em>unrelated</em> route answer the
     * runtime's at-capacity refusal.
     *
     * <p>Three things this case has to get right, each of which made an earlier draft worthless.
     * <strong>Wait before sampling the canary</strong> — opening {@code maxInFlight} requests at
     * once saturates admission by construction, so a canary fired immediately is refused on
     * correct code too. <strong>Assert on an unrelated route, never on health</strong> — the
     * admission gate exempts the health and asset prefixes, so a node keeps reporting healthy
     * while every application route is being refused. <strong>Red before green</strong> — before
     * this change the volley parks inside the limiter's monitor holding its permits, and the
     * canary is refused.
     */
    @Test
    void aStalledLedgerDoesNotConsumeNodeAdmission() throws Exception {
        Path home = prepareAppHome();
        CountDownLatch stalled = new CountDownLatch(1);
        TesseraqlRuntime node = TesseraqlRuntime.start(home, 0);
        try {
            node.context().bind(TesseraqlProperties.RATE_BUDGET_BEAN,
                    (RateBudget) (scopeKey, windowStart, want, budget) -> {
                        try {
                            stalled.await(40, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return want;
                    });

            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + node.port();
            // Comfortably above maxInFlight, which is workerThreads.orElse(10) * 4.
            AtomicInteger answered = new AtomicInteger();
            List<Thread> volley = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                volley.add(Thread.ofVirtual().start(() -> {
                    try {
                        client.send(HttpRequest.newBuilder(URI.create(base + "/limited")).build(),
                                HttpResponse.BodyHandlers.ofString());
                        answered.incrementAndGet();
                    } catch (IOException | InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            // Longer than the limiter's lease wait, so the volley has let its permits go.
            Thread.sleep(400);

            long start = System.nanoTime();
            HttpResponse<String> canary = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/unrelated")).build(),
                    HttpResponse.BodyHandlers.ofString());
            long canaryMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(canary.statusCode())
                    .as("a route that declares no rate limit is answered while the ledger stalls")
                    .isEqualTo(200);
            assertThat(canaryMs)
                    .as("and it is answered without queueing behind the stalled claim")
                    .isLessThan(2000);
            assertThat(answered.get())
                    .as("the volley itself was answered rather than parked on the ledger")
                    .isGreaterThanOrEqualTo(40);

            stalled.countDown();
            for (Thread request : volley) {
                request.join(2000);
            }
        } finally {
            stalled.countDown();
            node.close();
            try (var files = Files.walk(home)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Every SQLState in the cause chain — the precise signal, not the message text. */
    private static List<String> sqlStates(Throwable failure) {
        List<String> states = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                states.add(sql.getSQLState());
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return states;
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
                admission:
                  rateLimit:
                    requestsPerSecond: 5
                    scope: cluster
                sources:
                  main:
                    sql:
                      file: limited.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                """);
        // A route with no rate limit at all: the canary that proves a stalled ledger on the
        // limited route did not take the node's in-flight budget with it.
        Path unrelated = target.resolve("web/unrelated");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("unrelated.sql"), "select 1 as ok\n");
        Files.writeString(unrelated.resolve("get.yml"), """
                version: tesseraql/v1
                id: unrelated.get
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: unrelated.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                """);
        return target;
    }
}
