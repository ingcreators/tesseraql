package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The runtime-wide in-flight bound (docs/http-threading.md decision 3).
 *
 * <p>There was none: requests arriving while every worker was blocked in JDBC queued in Vert.x's
 * blocked-task queue, which has no bound. Beyond the bound the answer is now an immediate 503 that
 * a caller can retry, rather than a place in an invisible queue.
 */
@Testcontainers
class HttpAdmissionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(appHome)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /**
     * One worker, two in flight: the third request is refused rather than queued.
     *
     * <p>The refusal carries {@code Retry-After} and the code, because an unexplained 503 reads as
     * broken where a bounded one reads as busy.
     */
    @Test
    void aRequestBeyondTheBoundIsRefusedRatherThanQueued() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        HttpResponse<String> refused = get("/api/nap");

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).contains("1");
        assertThat(refused.body()).contains("TQL-RATE-4293");
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * Health is not refused while the runtime is at its bound.
     *
     * <p>It is checked before the permit: health is the one surface whose whole purpose is to be
     * answerable when nothing else is, and a runtime killed for failing to say "I am busy" has
     * turned a slowdown into an outage.
     *
     * <p><strong>And it is prompt, not merely admitted.</strong> Health was a Camel route, so
     * being let past the gate still left it queueing for a worker — bounded by {@code maxInFlight}
     * rather than unbounded, which was an improvement and not an answer. It is now answered on
     * the router, so the elapsed time here is a real assertion rather than a hopeful one.
     */
    @Test
    void healthIsNotRefusedWhileTheRuntimeIsAtItsBound() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        // Proves the gate is saturated for ordinary traffic at the moment health is asked.
        assertThat(get("/api/nap").statusCode()).isEqualTo(503);
        long startedAt = System.currentTimeMillis();
        HttpResponse<String> health = get("/_tesseraql/health/live");
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        // The one worker is inside pg_sleep for a second; this did not wait for it.
        assertThat(elapsedMs).isLessThan(500);
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * A dot segment does not buy a request its way past the gate.
     *
     * <p>The gate exempts the health and asset mounts, and it read the target as transmitted while
     * vertx-web routes on the normalized path. {@code /_tesseraql/health/../../api/nap} therefore
     * satisfied the carve-out and was then routed to {@code /api/nap}: the whole in-flight bound
     * was one dot segment away from not existing.
     *
     * <p>Driven over a raw socket on purpose. {@link java.net.http.HttpClient} normalizes a
     * request target before it leaves, so the same case built on it would send {@code /api/nap}
     * and pass against the defect.
     */
    @Test
    void aDotSegmentPastAnExemptMountStillSpendsAPermit() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        String pastHealth = raw("/_tesseraql/health/../../api/nap");
        String pastAssets = raw("/assets/../api/nap");

        assertThat(pastHealth).contains("503").contains("TQL-RATE-4293");
        assertThat(pastAssets).contains("503").contains("TQL-RATE-4293");
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * The carve-out follows the router, so a spelling the router accepts is exempt too.
     *
     * <p>{@code %68} is an unreserved escape: vertx-web decodes it before matching, so this
     * request reaches the health route. Testing the transmitted target instead refused it while
     * the runtime was busy — the carve-out and the routing disagreed in both directions.
     */
    @Test
    void aPercentEncodedSpellingOfTheHealthMountIsStillExempt() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        String health = raw("/_tesseraql/%68ealth/live");

        assertThat(health).contains("200").contains("UP");
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * A Unicode route is charged one permit, not two.
     *
     * <p>{@code UnicodePaths} reroutes such a request in its decoded form and routing restarts
     * from the first handler, so the gate's position behind it is what keeps the encoded spelling
     * and the decoded one from each taking a permit. One nap holds one of the two permits here;
     * a double charge would need both and answer 503.
     */
    @Test
    void aUnicodeRouteIsChargedOnePermit() throws Exception {
        CompletableFuture<HttpResponse<String>> saturating = CompletableFuture
                .supplyAsync(() -> get("/api/nap"));
        awaitInFlight();

        HttpResponse<String> unicode = get("/%E5%8F%97%E6%B3%A8");

        assertThat(unicode.statusCode()).isEqualTo(200);
        assertThat(saturating.get().statusCode()).isEqualTo(200);
    }

    /** Both saturating requests have taken their permits before the assertion runs. */
    private static void awaitInFlight() throws InterruptedException {
        Thread.sleep(700);
    }

    /**
     * Sends {@code target} exactly as written and returns the status line, headers and body.
     *
     * <p>The JDK client rewrites a target carrying dot segments before it reaches the wire, which
     * is the whole property under test here.
     */
    private static String raw(String target) {
        try (java.net.Socket socket = new java.net.Socket("localhost", runtime.port())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\n"
                    + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static HttpResponse<String> get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + path))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-http-admission-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: admission-it
                  http:
                    workerThreads: 1
                    maxInFlight: 2
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path nap = target.resolve("web/api/nap");
        Files.createDirectories(nap);
        Files.writeString(nap.resolve("get.yml"), """
                version: tesseraql/v1
                id: nap
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: nap.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(nap.resolve("nap.sql"), "select pg_sleep(3) as nap\n");

        // A Unicode route path, so UnicodePaths reroutes the request and routing restarts: the
        // gate must charge the decoded spelling once, not the encoded one as well.
        Path juchu = target.resolve("web/受注");
        Files.createDirectories(juchu);
        Files.writeString(juchu.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注一覧
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: 受注.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(juchu.resolve("受注.sql"), "select 1 as 受注番号\n");
        return target;
    }
}
