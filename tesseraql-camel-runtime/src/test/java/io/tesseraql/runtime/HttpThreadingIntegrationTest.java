package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The HTTP worker pool is this runtime's ceiling on concurrent route execution, and it is a
 * declared number (docs/http-threading.md decision 1).
 *
 * <p>It was inherited. {@code camel-platform-http-vertx} hands every exchange to
 * {@code executeBlocking}, so requests run on the Vert.x worker pool — and with no
 * {@code VertxOptions} in the registry that pool was Vert.x's default of 20, a size chosen for a
 * framework where blocking is the exception. Against the connection pool's default of 10, half
 * those workers could only ever wait in {@code getConnection()}.
 */
@Testcontainers
class HttpThreadingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome("2");
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        delete(appHome);
    }

    /**
     * Six concurrent requests against two workers run two at a time.
     *
     * <p>The thread census is the direct evidence — no more worker threads exist than were asked
     * for. The elapsed floor is the consequence, and it is what a reader actually cares about:
     * the pool size is the concurrency ceiling, not a hint.
     */
    @Test
    void theWorkerPoolIsTheConcurrencyCeiling() throws Exception {
        long startedAt = System.currentTimeMillis();
        List<CompletableFuture<HttpResponse<String>>> inFlight = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> get("/api/nap")))
                .limit(6)
                .toList();
        for (CompletableFuture<HttpResponse<String>> request : inFlight) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;

        // Six one-second statements, two at a time: three waves. Twenty workers would have run
        // them in one.
        assertThat(elapsedMs).isGreaterThan(2_500);

        long workers = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("vert.x-worker-thread"))
                .count();
        assertThat(workers).isLessThanOrEqualTo(2);
    }

    /**
     * A classpath asset is served while every worker is blocked in the database.
     *
     * <p>This is the coupling the move onto the router removed (docs/http-threading.md decision
     * 6). Assets were a Camel route, so a stylesheet took one of the same workers a slow query
     * holds and a page's worth of them queued behind whatever the database was doing. Framework
     * assets and vendored WebJars — the bulk of a page's bytes — are now answered from memory on
     * the event loop, and take no worker at all.
     */
    @Test
    void aClasspathAssetIsServedWhileEveryWorkerIsBlocked() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> blocking = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> get("/api/nap")))
                .limit(4)
                .toList();
        // Long enough for both workers to be inside pg_sleep, with two more requests queued.
        Thread.sleep(400);

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> asset = get("/assets/_tesseraql/icons.svg");
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(asset.statusCode()).isEqualTo(200);
        // Four one-second statements against two workers is two seconds of queue. This does not
        // join it, because it never asks for a worker.
        assertThat(elapsedMs).isLessThan(500);
        for (CompletableFuture<HttpResponse<String>> request : blocking) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * A file is served while every worker is blocked in the database.
     *
     * <p>The half moving onto the router could not reach by itself, and the reason the test above
     * was named for the other one. {@code sendFile} streams instead of buffering, but Vert.x
     * dispatches the reads behind it to the worker pool, so a file asset stayed coupled to
     * exactly what leaving the Camel route was meant to escape — 1689 ms on this fixture against
     * a classpath asset's 7 ms. Reading it on threads the runtime owns is what closes that gap.
     */
    @Test
    void aFileAssetIsServedWhileEveryWorkerIsBlocked() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> blocking = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> get("/api/nap")))
                .limit(4)
                .toList();
        // Long enough for both workers to be inside pg_sleep, with two more requests queued.
        Thread.sleep(400);

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> asset = get("/assets/app.css");
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(elapsedMs).isLessThan(500);
        for (CompletableFuture<HttpResponse<String>> request : blocking) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * A file larger than one chunk arrives whole, and says how long it is.
     *
     * <p>Streaming is only worth having if it delivers what buffering delivered. The read is a
     * loop now — each pass waiting for the connection to take the previous chunk — and a mistake
     * in it would arrive as a stylesheet that stops halfway rather than as an error.
     */
    @Test
    void aFileLargerThanOneChunkArrivesWhole() {
        HttpResponse<String> asset = get("/assets/large.txt");

        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.body()).isEqualTo(largeAsset());
        assertThat(asset.headers().firstValue("Content-Length"))
                .contains(String.valueOf(largeAsset().length()));
    }

    /**
     * A file's validator does not require reading the file.
     *
     * <p>The old route hashed the content, so answering "not modified" — the cheapest response
     * there is — cost a full read and a full SHA-256. A weak {@code (mtime, size)} validator is
     * what lets a file be streamed instead of buffered.
     */
    @Test
    void aFileAssetAnswers304FromAWeakValidator() throws Exception {
        HttpResponse<String> first = get("/assets/app.css");
        String etag = first.headers().firstValue("ETag").orElseThrow();

        assertThat(etag).startsWith("W/");

        HttpRequest conditional = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/assets/app.css"))
                .header("If-None-Match", etag)
                .build();
        HttpResponse<String> second = HttpClient.newHttpClient()
                .send(conditional, HttpResponse.BodyHandlers.ofString());

        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).isEmpty();
    }

    /**
     * A datasource that declares no pool settings gets TesseraQL's defaults, not Hikari's.
     *
     * <p>They were the driver pool's, so the answer to "how many connections will this open"
     * lived in a dependency's release notes and could change when that dependency changed its
     * mind. The size matches the worker pool deliberately: a worker that cannot get a connection
     * is a thread doing nothing but waiting.
     */
    @Test
    void anUndeclaredPoolTakesTesseraqlsDefaults() {
        com.zaxxer.hikari.HikariDataSource main = runtime.camelContext().getRegistry()
                .lookupByNameAndType("main", com.zaxxer.hikari.HikariDataSource.class);

        assertThat(main).isNotNull();
        assertThat(main.getMaximumPoolSize()).isEqualTo(10);
        assertThat(main.getConnectionTimeout()).isEqualTo(30_000L);
    }

    /**
     * A thread count that is not a positive integer refuses at startup.
     *
     * <p>A pool sized from a typo is worse than one left at its default: the runtime starts, and
     * only the load that needed the threads finds out.
     */
    @Test
    void aThreadCountThatIsNotPositiveRefusesAtStartup() throws Exception {
        Path broken = prepareAppHome("0");
        try {
            assertThatThrownBy(() -> TesseraqlRuntime.start(broken, freePort()))
                    .hasMessageContaining("tesseraql.http.workerThreads")
                    .hasMessageContaining("at least 1");
        } finally {
            delete(broken);
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

    /** Three chunks and a remainder, so the read loop runs several times and ends part-way. */
    private static String largeAsset() {
        StringBuilder text = new StringBuilder();
        for (int line = 0; text.length() < 200_000; line++) {
            text.append("/* line ").append(line).append(" */\n");
        }
        return text.toString();
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

    private static Path prepareAppHome(String workerThreads) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-http-threading-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: threading-it
                  http:
                    workerThreads: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(workerThreads, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
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
        Files.writeString(nap.resolve("nap.sql"), "select pg_sleep(1) as nap\n");

        Path assets = target.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("app.css"), ":root { --tql-test: 1; }\n");
        Files.writeString(assets.resolve("large.txt"), largeAsset());
        return target;
    }
}
