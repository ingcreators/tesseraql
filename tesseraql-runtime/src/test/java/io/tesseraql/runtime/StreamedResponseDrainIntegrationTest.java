package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.HttpMounts;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The completion drain runs after the wire write, not before it (docs/vertx-native.md
 * decision 5).
 *
 * <p>The completions delete a streamed body's backing file, release the route's permits and end
 * its span — and the runner used to drain them in its own {@code finally}, before the edge had
 * read a single byte. On Linux the spool deletion still streamed by unlink-while-open semantics;
 * on Windows it leaked the file, and on a blob store it truncated the download. The route below
 * pins the order directly: its body refuses to be read once the completion has run, so a drain
 * that fires early fails the download rather than passing by filesystem courtesy.
 */
@Testcontainers
class StreamedResponseDrainIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Big enough for several chunks of the edge's 64k streaming loop. */
    private static final int BODY_BYTES = 300 * 1024;

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path spool;
    static final AtomicBoolean drained = new AtomicBoolean();

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());

        byte[] content = new byte[BODY_BYTES];
        for (int at = 0; at < content.length; at++) {
            content[at] = (byte) (at % 251);
        }
        spool = Files.createTempFile("tesseraql-drain-order", ".bin");
        Files.write(spool, content);

        Pipelines.of(runtime.context()).compiling(List.of())
                .pipeline("stream.order")
                .process(exchange -> {
                    InputStream open = Files.newInputStream(spool);
                    exchange.setBody(new InputStream() {
                        @Override
                        public int read() throws IOException {
                            byte[] one = new byte[1];
                            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                        }

                        @Override
                        public int read(byte[] into, int off, int len) throws IOException {
                            if (drained.get()) {
                                throw new IOException(
                                        "the drain ran before the body was written");
                            }
                            return open.read(into, off, len);
                        }

                        @Override
                        public void close() throws IOException {
                            open.close();
                        }
                    });
                    exchange.response().header("Content-Type", "application/octet-stream");
                    exchange.addOnCompletion(done -> {
                        drained.set(true);
                        try {
                            Files.deleteIfExists(spool);
                        } catch (IOException undeletable) {
                            throw new java.io.UncheckedIOException(undeletable);
                        }
                    });
                });
        HttpMounts.of(runtime.context()).mount("GET", "/stream-order", "stream.order");
        runtime.context().lookup(RouteEdge.BEAN, RouteEdge.class).refreshAll();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (spool != null) {
            Files.deleteIfExists(spool);
        }
        delete(appHome);
    }

    @Test
    void theBodyIsOnTheWireBeforeTheCompletionsRun() throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + "/stream-order"))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        // The whole body arrived: no read raced the completion that deletes its file.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(BODY_BYTES);

        // And the completion still ran — deferred is not dropped: the spool is gone.
        long deadline = System.currentTimeMillis() + 5_000;
        while (Files.exists(spool) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(drained).isTrue();
        assertThat(Files.exists(spool)).isFalse();
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-drain-order-app");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: drain-order
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
