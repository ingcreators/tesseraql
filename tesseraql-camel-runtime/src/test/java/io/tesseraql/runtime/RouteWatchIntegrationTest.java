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
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The {@code serve --watch} instant loop end to end: saving a route source on disk — no
 * Studio, no explicit reload call — hot-reloads the route through the file watcher, a new
 * route directory mounts as it is created, and a broken save serves its compile error as a
 * 500 stub without killing the watcher. Its own runtime and app, mirroring
 * {@link ReloadContentDiffIntegrationTest}, so the file edits cannot disturb a shared
 * fixture.
 */
@Testcontainers
class RouteWatchIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static final List<String> WATCH_LINES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        runtime = TesseraqlRuntime.start(appHome, port);
        runtime.watchRoutes(WATCH_LINES::add);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void savingOnDiskReloadsAndABrokenSaveStubsWithoutKillingTheWatcher() throws Exception {
        assertThat(get("/api/ping").body()).contains("v1");

        // Save the 2-way SQL in "an editor" (plain file write; no Studio apply, no reload
        // call): the watcher debounces the save and hot-reloads the route.
        Files.writeString(appHome.resolve("web/api/ping/ping.sql"),
                "select 'v2' as version\n");
        assertThat(await("/api/ping", response -> response.body().contains("v2")).body())
                .contains("v2");
        assertThat(WATCH_LINES).anySatisfy(line -> assertThat(line)
                .contains("Watch: reloaded routes (1 changed)")
                .contains("web/api/ping/ping.sql changed"));

        // A brand-new route directory joins the watch as it appears and mounts on save.
        Path pong = appHome.resolve("web/api/pong");
        Files.createDirectories(pong);
        Files.writeString(pong.resolve("pong.sql"), "select 'pong' as answer\n");
        Files.writeString(pong.resolve("get.yml"), routeYaml("pong", "pong"));
        assertThat(await("/api/pong", response -> response.statusCode() == 200).body())
                .contains("pong");

        // A broken save (unknown recipe) does NOT kill the watcher or the server: the route
        // serves its compile error as a 500 stub (TQL-CAMEL-3103), neighbors keep serving.
        Files.writeString(appHome.resolve("web/api/ping/get.yml"),
                routeYaml("ping", "ping").replace("recipe: query-json",
                        "recipe: no-such-recipe"));
        HttpResponse<String> stub = await("/api/ping",
                response -> response.statusCode() == 500);
        assertThat(stub.statusCode()).isEqualTo(500);
        assertThat(stub.body()).contains("TQL-CAMEL-3103").contains("no-such-recipe");
        assertThat(get("/api/pong").statusCode()).isEqualTo(200);
        assertThat(WATCH_LINES).anySatisfy(line -> assertThat(line)
                .contains("failed to compile").contains("no-such-recipe"));

        // Fixing the file in place recovers on the next save — the watcher is still alive.
        Files.writeString(appHome.resolve("web/api/ping/ping.sql"),
                "select 'v3' as version\n");
        Files.writeString(appHome.resolve("web/api/ping/get.yml"), routeYaml("ping", "ping"));
        assertThat(await("/api/ping", response -> response.statusCode() == 200
                && response.body().contains("v3")).body()).contains("v3");

        // Deleting the new route's directory un-mounts its endpoint.
        Files.delete(pong.resolve("get.yml"));
        Files.delete(pong.resolve("pong.sql"));
        Files.delete(pong);
        assertThat(await("/api/pong", response -> response.statusCode() == 404).statusCode())
                .isEqualTo(404);
    }

    /** Polls {@code path} until the response satisfies {@code until} (or 30s elapse). */
    private static HttpResponse<String> await(String path,
            Predicate<HttpResponse<String>> until) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<String> last = get(path);
        while (!until.test(last) && System.nanoTime() < deadline) {
            Thread.sleep(150);
            last = get(path);
        }
        return last;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-route-watch-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: route-watch-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      rolesClaim: roles
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path ping = target.resolve("web/api/ping");
        Files.createDirectories(ping);
        Files.writeString(ping.resolve("ping.sql"), "select 'v1' as version\n");
        Files.writeString(ping.resolve("get.yml"), routeYaml("ping", "ping"));
        return target;
    }

    /** A trivial public query-json route document over {@code <sql>.sql}. */
    private static String routeYaml(String id, String sql) {
        return """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: %s.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """.formatted(id, sql);
    }
}
