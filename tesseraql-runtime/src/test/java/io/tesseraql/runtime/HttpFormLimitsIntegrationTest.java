package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The form decoder's bounds are the framework's (docs/http-edge-robustness.md decisions 4
 * and 5).
 *
 * <p>The runtime built its server from a bare {@code HttpServerOptions}, so all three of
 * Vert.x's form-decoding defaults were in force: 8192 bytes per attribute, 256 fields, 1024
 * buffered bytes. The first is the defect — a large textarea was refused with a plain 400 whose
 * body read {@code java.io.IOException: Size exceed allowed maximum capacity}, while
 * {@code tesseraql.http.maxBodyBytes}, the bound this framework publishes for exactly those
 * bytes, never got to speak.
 *
 * <p>Driven over raw sockets throughout. These cases turn on the transfer encoding and on how
 * the body is chunked onto the wire, and neither is something an HTTP client library lets a
 * caller state.
 */
@Testcontainers
class HttpFormLimitsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** One megabyte, declared, so the ceiling under test is a number this test chose. */
    private static final long BODY_LIMIT = 1_048_576L;

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

    /** The control: an ordinary small field round-trips, so a null below means size, not wiring. */
    @Test
    void anOrdinaryFormFieldRoundTrips() throws Exception {
        String answer = postField(5, true);

        assertThat(answer).startsWith("HTTP/1.1 200");
        assertThat(recordedLength()).isEqualTo(5);
    }

    /**
     * A form field far over Vert.x's 8192-byte attribute default is accepted.
     *
     * <p>500 KB is well inside the declared body limit and about 170,000 characters of Japanese
     * — the shape of an ordinary long-form textarea, which this runtime refused.
     */
    @Test
    void aFormFieldOverTheTransportsInheritedCeilingIsAccepted() throws Exception {
        String answer = postField(500_000, true);

        assertThat(answer).startsWith("HTTP/1.1 200");
        assertThat(recordedLength()).isEqualTo(500_000);
    }

    /** The same, with a declared length rather than chunked: the ceiling is not a chunking quirk. */
    @Test
    void aLargeFormFieldIsAcceptedWithADeclaredLength() throws Exception {
        String answer = postField(500_000, false);

        assertThat(answer).startsWith("HTTP/1.1 200");
        assertThat(recordedLength()).isEqualTo(500_000);
    }

    /**
     * Over the body limit, the answer is the framework's 413 — not the decoder's 400.
     *
     * <p>This is the race the headroom exists for, and it only appears on a chunked body: the
     * transport offers every chunk to the decoder before the body handler counts it, so a form
     * ceiling set equal to the body limit lets the decoder refuse first. Measured against
     * vertx-core 5.1.6, equal bounds answer {@code 400 Bad Request} here, where
     * docs/deployment.md promises 413 and {@code TQL-SEC-4150}.
     */
    @Test
    void aChunkedBodyOverTheLimitAnswersTheFrameworksRefusalNotTheDecodersLocal()
            throws Exception {
        String answer = postField(4_000_000, true);

        assertThat(answer).startsWith("HTTP/1.1 413");
        assertThat(answer).contains("TQL-SEC-4150");
    }

    /** And with a declared length, where the body handler can refuse before a byte arrives. */
    @Test
    void aDeclaredBodyOverTheLimitAnswersTheFrameworksRefusal() throws Exception {
        String answer = postField(4_000_000, false);

        assertThat(answer).startsWith("HTTP/1.1 413");
        assertThat(answer).contains("TQL-SEC-4150");
    }

    /**
     * A form with more fields than Vert.x's inherited 256 is accepted.
     *
     * <p>This is the count half of the same defect, and it is not hypothetical: a snapshot list
     * renders one hidden membership field per row inside one form, so any such page past roughly
     * 250 rows posted a form the decoder refused with a marker-less 400 — on a surface this
     * project shipped with a default cap of 500. The same wall stopped the largest decision
     * table Studio can render from being saved.
     *
     * <p>One declared field and 299 the route never reads, which is the shape a membership form
     * actually has: the page posts far more inputs than the statement binds.
     */
    @Test
    void aFormWithMoreFieldsThanTheTransportsInheritedCountIsAccepted() throws Exception {
        StringBuilder body = new StringBuilder("v=xyz");
        for (int row = 0; row < 299; row++) {
            body.append("&keys=row-").append(row);
        }

        String answer = post(body.toString().getBytes(StandardCharsets.US_ASCII), true);

        assertThat(answer).startsWith("HTTP/1.1 200");
        assertThat(recordedLength()).isEqualTo(3);
    }

    /**
     * The buffered bound stays finite, and the connection recovers.
     *
     * <p>{@code maxFormBufferedBytes} is left at Vert.x's 1024 deliberately: it bounds the
     * undecoded remainder rather than a field, and it is the decoder's only self-termination.
     * A body that can never be parsed — 5,000 bytes of field name with no {@code =} — must
     * still be refused rather than accumulated.
     */
    @Test
    void aBodyTheDecoderCanNeverParseIsStillRefused() throws Exception {
        String answer = post("x".repeat(5_000).getBytes(StandardCharsets.US_ASCII), true);

        assertThat(answer).startsWith("HTTP/1.1 400");
        // And the refusal is typed, end to end through the runtime. Before the router gained a
        // 400 handler this was a body of literally "Bad Request" with a stack trace in the log.
        assertThat(answer).contains("TQL-FIELD-2012");
    }

    /**
     * The same refusal reaches an htmx caller as a fragment it can show.
     *
     * <p>The bootstrap declines to swap a 4xx whose body carries no allowance marker, so without
     * this the page renders nothing at all — the asymmetry the 413 already avoided.
     */
    @Test
    void anHtmxCallerGetsTheRefusalAsAFragment() throws Exception {
        String answer = post("x".repeat(5_000).getBytes(StandardCharsets.US_ASCII), true,
                "HX-Request: true\r\n");

        assertThat(answer).startsWith("HTTP/1.1 400");
        assertThat(answer).contains("text/html");
        assertThat(answer).contains("data-hc-field-errors");
        assertThat(answer).contains("TQL-FIELD-2012");
    }

    /** What the command route recorded: proof the whole field arrived, not just that it was let in. */
    private static int recordedLength() throws Exception {
        java.net.http.HttpResponse<String> read = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + runtime.port() + "/api/echo/last")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        java.util.regex.Matcher chars = java.util.regex.Pattern
                .compile("\"chars\"\\s*:\\s*(-?\\d+)")
                .matcher(read.body());
        assertThat(chars.find()).as("no chars in %s", read.body()).isTrue();
        return Integer.parseInt(chars.group(1));
    }

    /** Posts a single urlencoded field of {@code bytes} characters and returns the raw answer. */
    private static String postField(int bytes, boolean chunked) throws Exception {
        return post(("v=" + "x".repeat(bytes)).getBytes(StandardCharsets.US_ASCII), chunked);
    }

    private static String post(byte[] payload, boolean chunked) throws Exception {
        return post(payload, chunked, "");
    }

    private static String post(byte[] payload, boolean chunked, String extraHeaders)
            throws Exception {
        try (Socket socket = new Socket("localhost", runtime.port())) {
            socket.setSoTimeout(60_000);
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder("POST /api/echo HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: application/x-www-form-urlencoded\r\n"
                    + extraHeaders);
            head.append(chunked
                    ? "Transfer-Encoding: chunked\r\n"
                    : "Content-Length: " + payload.length + "\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // Written in transport-sized deliveries and flushed between them, because whether
            // the decoder or the body handler sees the crossing first is the property here.
            //
            // A write that fails is an EXPECTED end of an over-limit upload, not a failure of
            // the case: the server answers 413 and stops reading while the client is still
            // sending, so the client's next write meets a closed pipe. Whether that happens
            // before the last byte leaves depends on the machine, which is why treating it as
            // an outcome made this a flake — it passed locally and failed on CI. The answer is
            // already on the wire either way, so the write stops and the read happens anyway.
            int chunk = 8192;
            for (int i = 0; i < payload.length; i += chunk) {
                int size = Math.min(chunk, payload.length - i);
                try {
                    if (chunked) {
                        out.write((Integer.toHexString(size) + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(payload, i, size);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    } else {
                        out.write(payload, i, size);
                    }
                    out.flush();
                } catch (java.net.SocketException refusedMidWrite) {
                    return read(socket);
                }
            }
            if (chunked) {
                try {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (java.net.SocketException refusedMidWrite) {
                    return read(socket);
                }
            }
            return read(socket);
        } catch (java.net.SocketException beforeAnyAnswer) {
            // Only a failure with nothing readable behind it reaches here.
            return "SOCKET " + beforeAnyAnswer.getMessage();
        }
    }

    private static String read(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder answer = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            answer.append(line).append('\n');
        }
        return answer.toString();
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-form-limits-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: form-limits-it
                  http:
                    maxBodyBytes: %d
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(BODY_LIMIT, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path migrations = target.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"),
                "create table form_echo (id integer primary key, chars integer);\n"
                        + "insert into form_echo (id, chars) values (1, -1);\n");

        // A command route, because a query route reads a JSON body and this test posts a form.
        Path echo = target.resolve("web/api/echo");
        Files.createDirectories(echo);
        Files.writeString(echo.resolve("post.yml"), """
                version: tesseraql/v1
                id: echo
                kind: route
                recipe: command-json
                security:
                  auth: public
                  csrf: false
                input:
                  v:
                    type: string
                    required: true
                steps:
                  - id: main
                    sql:
                      file: echo.sql
                      mode: update
                      params:
                        v: body.v
                response:
                  json:
                    body:
                      ok: true
                """);
        Files.writeString(echo.resolve("echo.sql"),
                "update form_echo set chars = length(/* v */ 'x') where id = 1\n");

        // Read back what the command recorded, on a shape that needs no request body at all.
        Path last = target.resolve("web/api/echo/last");
        Files.createDirectories(last);
        Files.writeString(last.resolve("get.yml"), """
                version: tesseraql/v1
                id: echo.last
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: last.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(last.resolve("last.sql"), "select chars from form_echo where id = 1\n");
        return target;
    }
}
