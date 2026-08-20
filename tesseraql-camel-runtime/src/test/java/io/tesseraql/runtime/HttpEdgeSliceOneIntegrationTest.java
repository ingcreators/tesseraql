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
 * Slice 1 of docs/http-edge.md: one real route, served both ways, measured.
 *
 * <p>The dispatch measurement in {@code HttpEdgeDispatchIntegrationTest} used a hand-written
 * blocking call, which proves what a thread pool does and nothing about this framework. This
 * serves an actual compiled route — its security gate, its binder, its {@code tesseraql-sql:}
 * producer, its response renderer, in the order the compiler put them — from a Vert.x handler on
 * a virtual thread, beside the Camel route it was compiled into.
 *
 * <p>Two questions, and the design says the second one decides whether anything after slice 1
 * happens at all: does the same pipeline produce the same answer off a route, and does it stop
 * queueing for a worker when it does.
 */
@Testcontainers
class HttpEdgeSliceOneIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String CAMEL_PATH = "/api/nap";
    private static final String EDGE_PATH = "/edge/api/nap";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        EdgeMount.install(runtime.camelContext(), runtime.port(), "nap", EDGE_PATH);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        delete(appHome);
    }

    /** The same processors, off a route, answer what they answer on one. */
    @Test
    void theSamePipelineProducesTheSameAnswer() {
        HttpResponse<String> viaCamel = get(CAMEL_PATH);
        HttpResponse<String> viaEdge = get(EDGE_PATH);

        assertThat(viaEdge.statusCode()).isEqualTo(viaCamel.statusCode());
        assertThat(viaEdge.body()).isEqualTo(viaCamel.body());
        assertThat(viaEdge.headers().firstValue("Content-Type"))
                .isEqualTo(viaCamel.headers().firstValue("Content-Type"));
    }

    /**
     * The number slice 1 exists to produce.
     *
     * <p>Two workers, four one-second statements already in flight. The Camel route waits for a
     * worker before its own statement starts; the same pipeline on the router does not, because
     * the only thing it needs is a connection and there are ten of those.
     */
    @Test
    void theEdgeDoesNotQueueForAWorkerAndTheRouteDoes() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> get(CAMEL_PATH)))
                .limit(6)
                .toList();
        Thread.sleep(400);

        // Both at once, against the same saturation: measured one after the other, the second
        // would be measured against a queue the first had already helped drain.
        CompletableFuture<Long> edge = CompletableFuture.supplyAsync(() -> timed(EDGE_PATH));
        CompletableFuture<Long> camel = CompletableFuture.supplyAsync(() -> timed(CAMEL_PATH));
        long edgeMs = edge.get();
        long camelMs = camel.get();
        HttpResponse<String> viaEdge = get(EDGE_PATH);
        HttpResponse<String> viaCamel = get(CAMEL_PATH);

        System.out.println("EDGE slice 1, 2 workers saturated by 6 one-second statements:");
        System.out.println("EDGE   via the Camel route  : " + camelMs + " ms");
        System.out.println("EDGE   via the router+vthread: " + edgeMs + " ms");

        assertThat(viaEdge.statusCode()).isEqualTo(200);
        assertThat(viaCamel.statusCode()).isEqualTo(200);
        // Its own second, and nobody else's.
        assertThat(edgeMs).isLessThan(1_800);
        // Queued behind the workers before its own second even starts.
        assertThat(camelMs).isGreaterThan(edgeMs + 500);
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /** How long one request took, for the comparison that has to be simultaneous. */
    private static long timed(String path) {
        long startedAt = System.currentTimeMillis();
        get(path);
        return System.currentTimeMillis() - startedAt;
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
        Path target = Files.createTempDirectory("tesseraql-http-edge-slice1");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: edge-slice1
                  http:
                    workerThreads: 2
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
        Files.writeString(nap.resolve("nap.sql"), "select pg_sleep(1) as nap\n");
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
