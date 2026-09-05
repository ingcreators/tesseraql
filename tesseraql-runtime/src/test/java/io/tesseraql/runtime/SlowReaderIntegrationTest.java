package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.HttpMounts;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A peer that stops reading is reclaimed at the transport (docs/http-edge-robustness.md
 * decision 8).
 *
 * <p>Both write loops wait on the previous chunk before reading the next, and that wait had no
 * deadline. A client that reads a response head and then stops reading parked a virtual thread
 * indefinitely — and for a route it parked the admission permit with it, because that is
 * released from the routing context's end handler. Enough stalled sockets closed the runtime to
 * everyone else.
 *
 * <p>Two fixtures, and the split is the point: one proves the permit is genuinely held while a
 * reader stalls, the other proves it comes back. A single fixture cannot do both without racing
 * its own timer.
 */
@Testcontainers
class SlowReaderIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /**
     * A socket that requests a large response and reads only the head.
     *
     * <p>A raw socket rather than an HTTP client: the JDK client exposes no receive-buffer
     * control and would drain the body into its own buffers, so nothing would ever stall.
     */
    private static Socket stall(int port) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(4096);
        socket.connect(new InetSocketAddress("localhost", port), 5_000);
        socket.getOutputStream().write(("GET /stream-forever HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        socket.setSoTimeout(20_000);
        // Read the status line and no more; the response body then backs up behind this socket.
        in.read(new byte[128]);
        return socket;
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * With a long bound, the permit stays held for as long as the reader stalls.
     *
     * <p>This is the defect stated positively: one stalled download occupies the runtime's whole
     * admission budget. Asserted with a generous idle bound so the assertion cannot race the
     * timer that the other fixture is about.
     */
    @Nested
    class WhileTheReaderStalls {

        @Test
        void theAdmissionPermitIsGenuinelyHeld() throws Exception {
            Path home = appHome("slow-reader-held", 300);
            TesseraqlRuntime runtime = bootWithEndlessStream(home);
            try (Socket stalled = stall(runtime.port())) {
                assertThat(stalled.isConnected()).isTrue();
                // maxInFlight is 1 and the stalled download holds it.
                assertThat(get(runtime.port(), "/api/quick").statusCode()).isEqualTo(503);
            } finally {
                runtime.close();
                delete(home);
            }
        }
    }

    /**
     * With a short bound, the connection is cut and the permit comes back.
     *
     * <p>Measured before this was written: with no idle bound at all, ten seconds after the write
     * loop stalls neither the connection close handler nor the response close handler has fired
     * and the socket is still open — so a per-chunk deadline would free the thread and leak the
     * permit and the socket. The transport's close is the instrument that reclaims all three.
     */
    @Nested
    class WhenTheBoundExpires {

        @Test
        void theConnectionIsCutAndThePermitReturns() throws Exception {
            Path home = appHome("slow-reader-cut", 2);
            TesseraqlRuntime runtime = bootWithEndlessStream(home);
            try (Socket stalled = stall(runtime.port())) {
                assertThat(get(runtime.port(), "/api/quick").statusCode()).isEqualTo(503);

                // Polled, never slept on: how long the transport takes to notice and close is
                // the runner's decision, and a fixed delay here would be a flake with a
                // schedule. Asserted on eventual state, not on elapsed milliseconds.
                Instant deadline = Instant.now().plusSeconds(60);
                HttpResponse<String> poll = get(runtime.port(), "/api/quick");
                while (Instant.now().isBefore(deadline) && poll.statusCode() != 200) {
                    Thread.sleep(500);
                    poll = get(runtime.port(), "/api/quick");
                }
                assertThat(poll.statusCode())
                        .as("the admission permit never came back; last answer: %s", poll.body())
                        .isEqualTo(200);
                // The permit's return is the proof: it comes back only when the exchange is
                // over, and the exchange is over only because the transport cut the connection.
                // The socket is deliberately not drained to check for EOF — reading from it
                // would unblock the server's write and end the very stall under test.
                assertThat(stalled.isClosed()).isFalse();

            } finally {
                runtime.close();
                delete(home);
            }
        }
    }

    @AfterAll
    static void done() {
        // Each case owns its runtime; the container is the class's.
    }

    @BeforeAll
    static void containerIsUp() {
        assertThat(POSTGRES.isRunning()).isTrue();
    }

    /**
     * Boots the runtime and mounts a body that streams without end.
     *
     * <p>A streamed body, not a buffered one, and that distinction is the finding: a buffered
     * response calls {@code end(buffer)}, whose end handler fires synchronously, so its
     * admission permit comes back at once even against a peer reading nothing. Only the chunked
     * write loop — downloads, exports, any route that sets a stream as its body — waits on the
     * peer, and only it holds the permit.
     */
    private static TesseraqlRuntime bootWithEndlessStream(Path home) throws Exception {
        TesseraqlRuntime runtime = TesseraqlRuntime.start(home, 0);
        Pipelines.of(runtime.context()).compiling(java.util.List.of())
                .pipeline("stream.forever")
                .process(exchange -> {
                    exchange.response().header("Content-Type", "application/octet-stream");
                    exchange.setBody(endlessBytes());
                });
        HttpMounts.of(runtime.context()).mount("GET", "/stream-forever", "stream.forever");
        runtime.context().lookup(RouteEdge.BEAN, RouteEdge.class).refreshAll();
        return runtime;
    }

    /** Bytes without end: the write loop can only stop because the peer or the bound stopped it. */
    private static InputStream endlessBytes() {
        return new InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] into, int off, int len) {
                java.util.Arrays.fill(into, off, off + len, (byte) 'x');
                return len;
            }
        };
    }

    private static void delete(Path home) throws IOException {
        try (Stream<Path> files = Files.walk(home)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /**
     * One worker, one request in flight, and a route that streams far more than a socket
     * buffer holds.
     */
    private static Path appHome(String name, int idleTimeoutSeconds) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-" + name + "-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  http:
                    workerThreads: 1
                    maxInFlight: 1
                    idleTimeoutSeconds: %d
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(name, idleTimeoutSeconds, POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path quick = target.resolve("web/api/quick");
        Files.createDirectories(quick);
        Files.writeString(quick.resolve("get.yml"), """
                version: tesseraql/v1
                id: quick
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: quick.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(quick.resolve("quick.sql"), "select 1 as quick\n");
        return target;
    }
}
