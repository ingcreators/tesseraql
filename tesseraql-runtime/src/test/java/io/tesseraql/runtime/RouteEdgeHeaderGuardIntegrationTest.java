package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.HttpMounts;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The wire guard on response headers (docs/vertx-native.md decision 1's surviving half).
 *
 * <p>A transport-owned name — framing, connection control, the {@code tql.} namespace — is
 * dropped with a warning rather than corrupting the response the server actually frames. A value
 * carrying a line break fails the request as a rendered 500: Vert.x refuses such a value anyway,
 * but its refusal used to fire inside {@code runOnContext}, past the virtual thread's net, and
 * the caller's connection hung until their own timeout — reachable from a form field, because
 * interpolated route headers carry caller data.
 */
@Testcontainers
class RouteEdgeHeaderGuardIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());

        Pipelines.of(runtime.context()).compiling(List.of())
                .pipeline("headers.reserved")
                .process(exchange -> {
                    exchange.response().header("Content-Type", "text/plain; charset=utf-8");
                    exchange.response().header("Content-Length", "5");
                    exchange.response().header("Connection", "close");
                    exchange.response().header("tql.acting.role", "admin");
                    exchange.response().header("X-Kept", "yes");
                    exchange.setBody("a body longer than the declared five bytes");
                });
        Pipelines.of(runtime.context()).compiling(List.of())
                .pipeline("headers.linebreak")
                .process(exchange -> {
                    exchange.response().header("X-Toast", "line one\r\nX-Smuggled: yes");
                    exchange.setBody("never sent");
                });
        HttpMounts.of(runtime.context()).mount("GET", "/headers-reserved", "headers.reserved");
        HttpMounts.of(runtime.context()).mount("GET", "/headers-linebreak", "headers.linebreak");
        runtime.context().lookup(RouteEdge.BEAN, RouteEdge.class).refreshAll();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        delete(appHome);
    }

    @Test
    void aTransportOwnedHeaderIsDroppedAndTheResponseStaysWhole() throws Exception {
        HttpResponse<String> response = get("/headers-reserved");

        assertThat(response.statusCode()).isEqualTo(200);
        // The body arrives whole: the declared Content-Length of 5 did not frame it.
        assertThat(response.body())
                .isEqualTo("a body longer than the declared five bytes");
        assertThat(response.headers().firstValue("X-Kept")).contains("yes");
        assertThat(response.headers().firstValue("tql.acting.role")).isEmpty();
        // The transport computed its own framing; the declared names were not copied.
        assertThat(response.headers().allValues("Content-Length"))
                .doesNotContain("5");
    }

    @Test
    void aHeaderValueWithALineBreakFailsTheRequestInsteadOfHangingIt() throws Exception {
        HttpResponse<String> response = get("/headers-linebreak");

        // A rendered 500, promptly — not a connection held open until the caller's timeout,
        // and not a smuggled second header.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("X-Smuggled")).isEmpty();
        assertThat(response.headers().firstValue("X-Toast")).isEmpty();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .timeout(Duration.ofSeconds(10))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-header-guard-app");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: header-guard
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
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
