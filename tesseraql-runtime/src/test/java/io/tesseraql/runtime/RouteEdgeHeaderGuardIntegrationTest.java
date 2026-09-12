package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.HttpMounts;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The wire guard on response headers (docs/vertx-native.md decision 1's surviving half).
 *
 * <p>A transport-owned name — framing, connection control, the {@code tql.} namespace — is
 * dropped with a warning rather than corrupting the response the server actually frames. A value
 * carrying a control character fails the request as a rendered 500: Vert.x refuses such a value
 * anyway, but its refusal used to fire inside {@code runOnContext}, past the virtual thread's
 * net, and the caller's connection hung until their own timeout on a buffered response — or a
 * streamed download went out as a 200 with its headers dropped. Reachable from a form field,
 * because interpolated route headers carry caller data. A tab stays accepted in an ordinary
 * header. A {@code Location} or {@code HX-Redirect} carrying anything outside printable ASCII
 * — a character above U+007F, a tab, a space — is refused the same way: every framework
 * writer percent-encodes before this edge, so a value that reaches it raw was written by a
 * writer nobody listed, and the loud 500 is what finds it.
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
        runtime = TesseraqlRuntime.start(appHome, 0);

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

        // Writers that bypass BasePath.url on purpose: what the backstop refuses and what it keeps.
        mount("edge.location.raw", "/edge/location-raw", 303, "Location", "/受注一覧");
        mount("edge.location.latin1", "/edge/location-latin1", 303, "Location", "/café");
        mount("edge.location.lower", "/edge/location-lower", 303, "location", "/受注一覧");
        mount("edge.location.201", "/edge/location-201", 201, "Location", "/受注一覧");
        mount("edge.location.tab", "/edge/location-tab", 303, "Location", "/\t/evil.example/x");
        mount("edge.location.space", "/edge/location-space", 303, "Location", "/a b");
        mount("edge.hx.raw", "/edge/hx-redirect-raw", 204, "HX-Redirect", "/受注一覧");
        mount("edge.hx.tab", "/edge/hx-redirect-tab", 204, "HX-Redirect", "/\t/evil.example/x");
        mount("edge.location.encoded", "/edge/location-encoded", 303, "Location", "/caf%C3%A9");
        buffered("edge.other.jp", "/edge/other-jp", "X-Name", "受注");
        for (String[] c : List.of(new String[]{"vt", "a\u000Bb"}, new String[]{"del", "a\u007Fb"},
                new String[]{"nul", "a\u0000b"}, new String[]{"tab", "a\tb"})) {
            buffered("edge.ctl." + c[0], "/edge/ctl-" + c[0], "X-Toast", c[1]);
            streamed("edge.stream.ctl." + c[0], "/edge/stream-ctl-" + c[0], c[1]);
        }
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

    // ---- the C0/DEL widening (ride-along: every control, both response shapes)

    @ParameterizedTest
    @ValueSource(strings = {"vt", "del", "nul"})
    void aHeaderValueWithAControlCharFailsTheRequestInsteadOfHangingIt(String control)
            throws Exception {
        HttpResponse<String> response = get("/edge/ctl-" + control);

        // A hang shows as an HttpTimeoutException from the client's own timeout: red, not slow.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("X-Toast")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vt", "del", "nul"})
    void aStreamedResponseWithAControlCharFailsWithItsHeadersNotHeaderless(String control)
            throws Exception {
        HttpResponse<String> response = get("/edge/stream-ctl-" + control);

        // Today's streamed shape was a 200 with Content-Type and Content-Disposition dropped.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).isPresent();
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
    }

    @Test
    void aTabInAHeaderValueStaysAccepted() throws Exception {
        // java.net.http folds an HTAB in a field value to a space: assert presence, never the byte.
        HttpResponse<String> buffered = get("/edge/ctl-tab");
        assertThat(buffered.statusCode()).isEqualTo(200);
        assertThat(buffered.headers().firstValue("X-Toast").orElse("")).matches("a[ \\t]b");

        HttpResponse<String> streamed = get("/edge/stream-ctl-tab");
        assertThat(streamed.statusCode()).isEqualTo(200);
        assertThat(streamed.headers().firstValue("Content-Type"))
                .contains("text/csv; charset=utf-8");
        assertThat(streamed.headers().firstValue("Content-Disposition").orElse(""))
                .matches("attachment; filename=\"a[ \\t]b\\.csv\"");
    }

    // ---- the backstop on a Location / HX-Redirect written past the seam

    @Test
    void aRawNonAsciiLocationIsRefusedAsA500NotShippedMangled() throws Exception {
        HttpResponse<String> response = get("/edge/location-raw");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void aRawLatin1LocationIsRefusedToo() throws Exception {
        HttpResponse<String> response = get("/edge/location-latin1");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void aRawNonAsciiHxRedirectIsRefusedAsA500() throws Exception {
        HttpResponse<String> response = get("/edge/hx-redirect-raw");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("HX-Redirect")).isEmpty();
    }

    @Test
    void aRawNonAsciiLocationIsRefusedWhateverItsSpellingOrStatus() throws Exception {
        // The response map keeps the writer's spelling; a 201's Location is a Location too.
        assertThat(get("/edge/location-lower").statusCode()).isEqualTo(500);
        assertThat(get("/edge/location-201").statusCode()).isEqualTo(500);
    }

    @Test
    void aLocationWithATabOrASpaceIsRefusedAsA500() throws Exception {
        // Read raw: java.net.http would fold the tab to a space and hide the byte.
        assertThat(rawStatus("/edge/location-tab")).isEqualTo(500);
        assertThat(rawStatus("/edge/location-space")).isEqualTo(500);
        assertThat(rawStatus("/edge/hx-redirect-tab")).isEqualTo(500);
    }

    @Test
    void anEncodedLocationPassesTheBackstop() throws Exception {
        HttpResponse<String> response = get("/edge/location-encoded");

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).contains("/caf%C3%A9");
    }

    @Test
    void aNonAsciiValueOnAnyOtherHeaderIsNotTheBackstopsBusiness() throws Exception {
        assertThat(get("/edge/other-jp").statusCode()).isEqualTo(200);
    }

    // ---- plumbing

    private static void mount(String id, String path, int status, String name, String value) {
        Pipelines.of(runtime.context()).compiling(List.of()).pipeline(id).process(exchange -> {
            exchange.response().status(status);
            exchange.response().header(name, value);
            exchange.setBody(status == 201 ? "{}" : "");
        });
        HttpMounts.of(runtime.context()).mount("GET", path, id);
    }

    private static void buffered(String id, String path, String name, String value) {
        Pipelines.of(runtime.context()).compiling(List.of()).pipeline(id).process(exchange -> {
            exchange.response().header("Content-Type", "text/plain; charset=utf-8");
            exchange.response().header(name, value);
            exchange.setBody("ok");
        });
        HttpMounts.of(runtime.context()).mount("GET", path, id);
    }

    private static void streamed(String id, String path, String value) {
        Pipelines.of(runtime.context()).compiling(List.of()).pipeline(id).process(exchange -> {
            exchange.response().header("Content-Type", "text/csv; charset=utf-8");
            exchange.response().header("Content-Disposition",
                    "attachment; filename=\"" + value + ".csv\"");
            exchange.setBody(new java.io.ByteArrayInputStream(
                    "name\r\nalpha\r\n".getBytes(StandardCharsets.UTF_8)));
        });
        HttpMounts.of(runtime.context()).mount("GET", path, id);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .timeout(Duration.ofSeconds(10))
                .build();
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** One raw HTTP/1.1 exchange; the status line only, read as sent. */
    private static int rawStatus(String path) throws Exception {
        try (Socket socket = new Socket("localhost", runtime.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            String head = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return Integer.parseInt(head.substring(9, 12));
        }
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

    private static void delete(Path target) throws IOException {
        if (target == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(target)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
