package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * Compiled routes served on the router rather than through {@code executeBlocking}
 * (docs/http-edge.md decision 1).
 *
 * <p>The runtime is configured with <b>two workers and eight connections</b> — the arrangement
 * that made the two numbers answer one question. Eight concurrent one-second statements take four
 * seconds if the worker pool is the ceiling and one if the connection pool is, so the elapsed
 * time is the whole assertion.
 *
 * <p>The other half is what happens to a request this adapter does not reproduce: it is handed
 * back to the Camel route still mounted behind it. A command route with a body proves that path,
 * because being handed back has to be invisible to the caller or it is not a hand-back.
 */
@Testcontainers
class HttpEdgeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final int CONCURRENT = 8;

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
        delete(appHome);
    }

    /**
     * The connection pool is the ceiling, and the worker pool is not in the way.
     *
     * <p>This is the arrangement docs/http-threading.md decision 1 had to pick a number for: two
     * libraries that never met, twenty threads against ten connections, and ten workers that
     * could only ever wait in {@code getConnection()}. A route that does not go through
     * {@code executeBlocking} does not queue for a worker at all, so the only bound left is the
     * one that answers the question the system actually asks.
     */
    @Test
    void theConnectionPoolIsTheCeilingAndTheWorkerPoolIsNotInTheWay() {
        long startedAt = System.currentTimeMillis();
        List<CompletableFuture<HttpResponse<String>>> inFlight = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> get("/api/nap")))
                .limit(CONCURRENT)
                .toList();
        inFlight.forEach(CompletableFuture::join);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        System.out.println("EDGE " + CONCURRENT + " concurrent 1s routes, 2 workers, "
                + "8 connections: " + elapsedMs + " ms");
        for (CompletableFuture<HttpResponse<String>> request : inFlight) {
            assertThat(request.join().statusCode()).isEqualTo(200);
        }
        // Two waves would be two seconds and four waves four. This is one.
        assertThat(elapsedMs).isLessThan(2_500);
    }

    /**
     * A form post is served here too, and it reads its fields.
     *
     * <p>This is what the hand-back used to cover. A form arrives as a {@code Map} body and as
     * headers, both appended so a repeated field is a list, because one binder reads the map and
     * another reads the header and a route written against either has to keep working. The
     * command runs, the redirect comes back with the field's value in it, and nothing about the
     * route changed.
     */
    @Test
    void aFormPostIsServedOnTheRouterAndReadsItsFields() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/touch"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("n=7"))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(post, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).hasValue("/api/nap?n=7");
    }

    /**
     * A form post is not bounded by the worker pool either.
     *
     * <p>The GET half moved first and a request carrying a body was handed back to Camel while
     * the adapter was still learning what a form looks like. With that gone, the assertion is the
     * same one and it covers every request the runtime serves: eight one-second commands against
     * two workers take one wave, not four.
     */
    @Test
    void aFormPostIsNotBoundedByTheWorkerPoolEither() {
        long startedAt = System.currentTimeMillis();
        List<CompletableFuture<HttpResponse<String>>> inFlight = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> postForm("n=3")))
                .limit(CONCURRENT)
                .toList();
        inFlight.forEach(CompletableFuture::join);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        System.out.println("EDGE " + CONCURRENT + " concurrent 1s form posts, 2 workers, "
                + "8 connections: " + elapsedMs + " ms");
        for (CompletableFuture<HttpResponse<String>> request : inFlight) {
            assertThat(request.join().statusCode()).isEqualTo(303);
        }
        assertThat(elapsedMs).isLessThan(2_500);
    }

    private static HttpResponse<String> postForm(String body) {
        try {
            HttpRequest post = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + "/api/touch"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(post, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
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
        Path target = Files.createTempDirectory("tesseraql-http-edge");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: edge-it
                  http:
                    workerThreads: 2
                    maxInFlight: 64
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                      maximumPoolSize: 8
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Files.createDirectories(target.resolve("db/migration"));
        Files.writeString(target.resolve("db/migration/V1__touched.sql"),
                "create table touched (n integer not null);\n");

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

        Path touch = target.resolve("web/api/touch");
        Files.createDirectories(touch);
        Files.writeString(touch.resolve("post.yml"), """
                version: tesseraql/v1
                id: touch
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  n:
                    type: integer
                    default: 5
                steps:
                  - id: main
                    sql:
                      file: touch.sql
                      mode: update
                      params:
                        n: query.n
                response:
                  redirect:
                    location: /api/nap?n={params.n}
                """);
        // Slow on purpose: the form post has to be shown leaving the worker pool too.
        Files.writeString(touch.resolve("touch.sql"),
                "insert into touched (n) select /* n */ -1 from pg_sleep(1)\n;\n");
        return target;
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
