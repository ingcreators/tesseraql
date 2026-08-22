package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Which number is the ceiling on concurrent blocking work (docs/http-edge.md decision 1): the
 * worker pool, or the connection pool?
 *
 * <p>Four dispatch modes do exactly the same thing — take a connection and hold it inside
 * {@code pg_sleep(1)} — against a runtime configured with <b>2 workers and 8 connections</b>.
 * Eight requests at once therefore take one second if the connection pool is the ceiling and four
 * if the worker pool is. The numbers decide what the design would otherwise only reason about.
 *
 * <p><strong>The surprise is the third mode.</strong> Vert.x's own
 * {@code ThreadingModel.VIRTUAL_THREAD} really does run the work on a virtual thread — and one
 * context is <em>one</em> thread, serial, so eight requests take eight seconds. It is an
 * execution context, not a concurrency mechanism. Getting concurrency from it means deploying a
 * pool of contexts and sizing it, which is the kind of number docs/http-threading.md exists to
 * stop the runtime inheriting. A virtual thread per request needs no such number.
 */
@Testcontainers
class HttpEdgeDispatchIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final int CONCURRENT = 8;

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Context virtualContext;
    /** Which threads Vert.x's virtual-thread context actually used, for the serial assertion. */
    static final java.util.Set<String> onVirtualThread = java.util.concurrent.ConcurrentHashMap
            .newKeySet();
    static final java.util.List<Context> virtualPool = new java.util.ArrayList<>();
    static final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        DataSource dataSource = runtime.context().lookup("main", DataSource.class);
        io.vertx.ext.web.Router router = runtime.context().lookup("tesseraqlHttpRouter",
                io.vertx.ext.web.Router.class);
        Vertx vertx = runtime.context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.VERTX_BEAN, Vertx.class);

        CompletableFuture<Context> deployed = new CompletableFuture<>();
        vertx.deployVerticle(new AbstractVerticle() {
            @Override
            public void start() {
                deployed.complete(vertx.getOrCreateContext());
            }
        }, new DeploymentOptions().setThreadingModel(ThreadingModel.VIRTUAL_THREAD));
        virtualContext = deployed.get();
        // One context per concurrent request, because one context turned out to be one thread.
        for (int instance = 0; instance < CONCURRENT; instance++) {
            CompletableFuture<Context> member = new CompletableFuture<>();
            vertx.deployVerticle(new AbstractVerticle() {
                @Override
                public void start() {
                    member.complete(vertx.getOrCreateContext());
                }
            }, new DeploymentOptions().setThreadingModel(ThreadingModel.VIRTUAL_THREAD));
            virtualPool.add(member.get());
        }

        // A: what the platform-http consumer did, kept as the baseline it measures against.
        router.route(HttpMethod.GET, "/spike/worker")
                .handler(ctx -> ctx.vertx().executeBlocking(() -> hold(dataSource), false)
                        .onComplete(done -> ctx.response().end("ok")));
        // B: Vert.x's own virtual-thread context.
        router.route(HttpMethod.GET, "/spike/virtual").handler(ctx -> {
            Context connection = ctx.vertx().getOrCreateContext();
            virtualContext.runOnContext(run -> {
                onVirtualThread.add(Thread.currentThread().getName());
                hold(dataSource);
                connection.runOnContext(end -> ctx.response().end("ok"));
            });
        });
        // D: a pool of virtual-thread contexts, round-robin — Vert.x's own answer to C.
        router.route(HttpMethod.GET, "/spike/virtual-pool").handler(ctx -> {
            Context connection = ctx.vertx().getOrCreateContext();
            virtualPool.get(Math.floorMod(next.getAndIncrement(), virtualPool.size()))
                    .runOnContext(run -> {
                        hold(dataSource);
                        connection.runOnContext(end -> ctx.response().end("ok"));
                    });
        });
        // C: a virtual thread of our own, the shape AssetRoutes already uses.
        router.route(HttpMethod.GET, "/spike/thread").handler(ctx -> {
            Context connection = ctx.vertx().getOrCreateContext();
            Thread.ofVirtual().start(() -> {
                hold(dataSource);
                connection.runOnContext(end -> ctx.response().end("ok"));
            });
        });
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        delete(appHome);
    }

    @Test
    void theConnectionPoolIsTheCeilingOnlyWhenTheWorkerPoolIsNotInTheWay() {
        // Warm the pool and the JIT so the first wave does not pay for both.
        get("/spike/thread");

        long worker = elapsedFor("/spike/worker");
        long virtual = elapsedFor("/spike/virtual");
        long pooled = elapsedFor("/spike/virtual-pool");
        long thread = elapsedFor("/spike/thread");

        System.out.println("EDGE " + CONCURRENT + " concurrent 1s statements, 2 workers, "
                + "8 connections:");
        System.out.println("EDGE   executeBlocking (today) : " + worker + " ms");
        System.out.println("EDGE   ThreadingModel.VIRTUAL  : " + virtual + " ms");
        System.out.println("EDGE   VIRTUAL x8 round-robin  : " + pooled + " ms");
        System.out.println("EDGE   Thread.ofVirtual()      : " + thread + " ms");
        System.out.println("EDGE   worker threads alive    : " + threads("vert.x-worker-thread"));

        // The worker pool is the ceiling: eight one-second statements, two at a time.
        assertThat(worker).isGreaterThan(3_000);
        assertThat(threads("vert.x-worker-thread")).isLessThanOrEqualTo(2);
        // One virtual-thread context is one virtual thread, and it runs its tasks in turn.
        assertThat(onVirtualThread).hasSize(1);
        assertThat(virtual).isGreaterThan(7_000);
        // Both ways of giving each request its own thread reach the connection pool's eight.
        assertThat(pooled).isLessThan(2_500);
        assertThat(thread).isLessThan(2_500);
    }

    private static long elapsedFor(String path) {
        long startedAt = System.currentTimeMillis();
        List<CompletableFuture<HttpResponse<String>>> inFlight = Stream
                .generate(() -> CompletableFuture.supplyAsync(() -> get(path)))
                .limit(CONCURRENT)
                .toList();
        inFlight.forEach(CompletableFuture::join);
        return System.currentTimeMillis() - startedAt;
    }

    private static long threads(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(prefix))
                .count();
    }

    /** Takes a connection and holds it for one second, the shape of a slow route. */
    private static String hold(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("select pg_sleep(1)");
            return "ok";
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
        Path target = Files.createTempDirectory("tesseraql-http-edge-dispatch");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: edge-spike
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
