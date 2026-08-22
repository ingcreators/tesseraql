package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.HttpMounts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
 * A body that fails mid-stream closes the connection instead of hanging it.
 *
 * <p>Once the head and the first chunks are on the wire no error body can follow, so the edge's
 * choices are three: end (faking a complete response), nothing (the caller waits on a chunked
 * response that never terminates, until <em>their</em> timeout — the silence an HTTP surface must
 * never answer with), or close (a truncated response, now). The edge closes — for a body whose
 * read throws {@code IOException} and for one that throws unchecked ({@code UncheckedIOException}
 * and kin, the way wrapped readers fail), which previously escaped to a net that tried to write a
 * 500 onto the committed response.
 */
@Testcontainers
class RouteEdgeStreamFailureIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Two edge chunks, so the head and real body bytes are committed before the failure. */
    private static final int GOOD_BYTES = 130 * 1024;

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());

        Pipelines.of(runtime.context()).compiling(List.of())
                .pipeline("stream.fail.io")
                .process(exchange -> {
                    exchange.response().header("Content-Type", "application/octet-stream");
                    exchange.setBody(failingAfter(GOOD_BYTES, false));
                });
        Pipelines.of(runtime.context()).compiling(List.of())
                .pipeline("stream.fail.unchecked")
                .process(exchange -> {
                    exchange.response().header("Content-Type", "application/octet-stream");
                    exchange.setBody(failingAfter(GOOD_BYTES, true));
                });
        HttpMounts.of(runtime.context()).mount("GET", "/stream-fail-io", "stream.fail.io");
        HttpMounts.of(runtime.context()).mount("GET", "/stream-fail-unchecked",
                "stream.fail.unchecked");
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
    void aBodyFailingWithIoExceptionClosesTheConnectionInsteadOfHanging() {
        Throwable failure = catchThrowable(() -> get("/stream-fail-io"));

        // The caller learns of the failure from the closed connection — an I/O error on the
        // truncated chunked body — not from their own request timeout expiring.
        assertThat(failure).isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void aBodyFailingUncheckedClosesTheConnectionTheSameWay() {
        Throwable failure = catchThrowable(() -> get("/stream-fail-unchecked"));

        assertThat(failure).isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);
    }

    /** Serves {@code good} readable bytes, then fails the way the flag says. */
    private static InputStream failingAfter(int good, boolean unchecked) {
        return new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] into, int off, int len) throws IOException {
                if (served >= good) {
                    if (unchecked) {
                        throw new UncheckedIOException(
                                new IOException("backing store went away"));
                    }
                    throw new IOException("backing store went away");
                }
                int giving = Math.min(len, good - served);
                java.util.Arrays.fill(into, off, off + giving, (byte) 7);
                served += giving;
                return giving;
            }
        };
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .timeout(Duration.ofSeconds(10))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-stream-failure-app");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: stream-failure
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
