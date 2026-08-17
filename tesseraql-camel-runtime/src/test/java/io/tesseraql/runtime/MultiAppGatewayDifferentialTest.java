package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The gateway is a route, not a rewrite: a request answered by an app's own port must be answered
 * identically through the gateway (docs/stack-architecture.md decision 13).
 *
 * <p>Every case here issues the <em>same</em> request twice — once at the app's internal port and
 * once at the gateway's — and asserts the two answers match. In suite mode both legs use the same
 * path, because the app is started serving the prefix it is fronted under
 * (docs/base-path.md decision 5), so the only difference between the legs is the hop under test.
 *
 * <p>Decision 13 asked for one gallery application's declarative suite run twice. That deliverable
 * is not implementable: every case kind {@code TestRunner} supports — {@code sql}, {@code contract},
 * {@code validate}, {@code decide}, {@code notify}, {@code http}, {@code messages},
 * {@code transition}, {@code dispatch} — evaluates in process against the app home and datasource,
 * and none issues an inbound request. docs/testing.md says so outright: a dispatch case is "the
 * button the UI actually calls, asserted without HTTP". Run twice, such a suite would compare an
 * in-process evaluation with itself and pass with the gateway switched off entirely. This drives
 * the app's real routes over real HTTP instead, which is what the deliverable was reaching for.
 *
 * <p>The cases past the plain routes are the ones a proxy breaks specifically: a body with no
 * declared length, a body past the old relay bounds in each direction, {@code HEAD}, a conditional
 * {@code 304}, a redirect's {@code Location}, and a cookie's {@code Path}.
 */
@Testcontainers
class MultiAppGatewayDifferentialTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String APP = "shop";

    /**
     * Headers that legitimately differ between the two legs, or that describe the hop rather than
     * the answer: the clock, and the framing the relay owns.
     */
    private static final Set<String> NOT_COMPARED = Set.of("date", "transfer-encoding",
            "connection", "keep-alive");

    static MultiAppGateway gateway;
    static Path installRoot;
    /** The app's own port — the "direct" leg. */
    static String direct;
    /** The gateway's port — the leg under test. */
    static String front;
    /** A second gateway serving and forwarding cleartext HTTP/2. */
    static MultiAppGateway h2Gateway;
    static String h2Front;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-gateway-diff");
        installApp();
        gateway = MultiAppGateway.start(installRoot, 0);
        direct = "http://localhost:" + gateway.appPort(APP);
        front = "http://localhost:" + gateway.port();
        h2Gateway = MultiAppGateway.start(installRoot, 0,
                new MultiAppGateway.Settings(true));
        h2Front = "http://localhost:" + h2Gateway.port();
    }

    @AfterAll
    static void stop() throws IOException {
        if (h2Gateway != null) {
            h2Gateway.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (installRoot != null) {
            deleteRecursively(installRoot);
        }
    }

    // ---------------------------------------------------------------- ordinary routes

    @Test
    void aJsonRouteAnswersIdenticallyThroughTheGateway() throws Exception {
        assertSame("/apps/" + APP + "/api/items");
    }

    @Test
    void aShellPageAnswersIdenticallyThroughTheGateway() throws Exception {
        assertSame("/apps/" + APP + "/users");
    }

    @Test
    void anAssetAnswersIdenticallyThroughTheGateway() throws Exception {
        assertSame("/apps/" + APP + "/assets/_tesseraql/tesseraql.css");
    }

    // ---------------------------------------------------------------- what a proxy breaks

    /**
     * A streaming export declares no length, so it answers chunked — and the gateway told the JDK
     * server {@code -1}, which in {@code com.sun.net.httpserver} means "no response body" rather
     * than "length unknown". Every chunked answer lost its body silently: a 200, the right headers,
     * and nothing after them.
     */
    @Test
    void aChunkedResponseWithNoDeclaredLengthKeepsItsBody() throws Exception {
        Captured answer = capture(direct, get("/apps/" + APP + "/api/export"));
        assertThat(answer.headers).as("the case is only meaningful without a declared length")
                .doesNotContainKey("content-length");
        assertThat(answer.length).as("and the app really does answer with a body").isPositive();

        assertSame("/apps/" + APP + "/api/export");
    }

    /**
     * Past the 64 MB ceiling the relay used to abort at, mid-body, after the status was already
     * sent — the truncation the export pipeline exists to avoid.
     */
    @Test
    void aResponsePastTheOldRelayBoundArrivesWhole() throws Exception {
        Captured answer = capture(front, get("/apps/" + APP + "/api/export-large"));

        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.length).as("larger than the ceiling that used to abort the relay")
                .isGreaterThan(64L * 1024 * 1024);
        assertSame("/apps/" + APP + "/api/export-large");
    }

    /**
     * The gateway no longer has a body cap of its own; the app's own limit is what governs.
     *
     * <p>It used to buffer the whole body into a {@code byte[]} and refuse past 10 MB, which
     * capped every attachment and import in every hosted app at a number the gateway chose.
     * Decision 13 removes it: the door routes, and the app behind it keeps whatever limits it
     * declares. Measured here as agreement — 8 MB passes both legs, and the 12 MB the app itself
     * refuses is refused identically through the gateway rather than differently. That the door
     * imposes no cap of its own at all is asserted against an origin without one, in
     * {@link SuiteRelayTest}.
     */
    @Test
    void theAppsOwnBodyLimitGovernsRatherThanTheGateways() throws Exception {
        Call accepted = measure(8 * 1024 * 1024);
        Captured relayed = capture(front, accepted);

        assertThat(relayed.status).as("past the 10 MB the gateway used to refuse at")
                .isEqualTo(200);
        assertThat(relayed.body).contains("\"n\":" + 8 * 1024 * 1024);
        assertSame(accepted);

        // And where the app does refuse, the refusal is the app's and arrives unchanged.
        assertSame(measure(12 * 1024 * 1024));
    }

    private static Call measure(int bytes) {
        return post("/apps/" + APP + "/api/measure", "{\"blob\":\"" + "x".repeat(bytes) + "\"}");
    }

    @Test
    void headAnswersIdenticallyThroughTheGateway() throws Exception {
        assertSame(head("/apps/" + APP + "/api/items"));
    }

    /** A conditional GET still answers 304, and the 304 still carries no body. */
    @Test
    void aConditionalGetStillAnswers304() throws Exception {
        String asset = "/apps/" + APP + "/assets/_tesseraql/tesseraql.css";
        String etag = capture(direct, get(asset)).headers.get("etag").getFirst();
        assertThat(etag).isNotBlank();

        Call conditional = get(asset).header("If-None-Match", etag);
        Captured answer = capture(front, conditional);

        assertThat(answer.status).isEqualTo(304);
        assertThat(answer.length).as("a 304 carries no body").isZero();
        assertSame(conditional);
    }

    /** A redirect's Location is the app's to choose; the gateway relays it unchanged. */
    @Test
    void aRedirectsLocationIsRelayedVerbatim() throws Exception {
        Captured answer = capture(front, post("/apps/" + APP + "/api/go", "{}"));

        assertThat(answer.status).isEqualTo(303);
        assertThat(answer.headers.get("location"))
                .as("the app resolved its own base path into the value; the gateway adds nothing")
                .containsExactly("/apps/" + APP + "/api/items");
        assertSame(post("/apps/" + APP + "/api/go", "{}"));
    }

    /**
     * A cookie's {@code Path} decides how far one sign-in reaches, and a suite issues the session
     * at the origin root on purpose ({@code CookiePath}: "A shared suite wants /, because one
     * sign-in reaching every application is the mode"). A relay that rewrote the attribute would
     * silently unshare the suite, so the attribute has to survive the hop byte for byte.
     */
    @Test
    void aCookiesPathIsRelayedVerbatim() throws Exception {
        Captured answer = capture(front, get("/apps/" + APP + "/api/cookie"));

        assertThat(answer.headers.get("set-cookie"))
                .containsExactly("tesseraql_sid=abc; Path=/; HttpOnly; SameSite=Lax");
        assertSame("/apps/" + APP + "/api/cookie");
    }

    /**
     * With cleartext HTTP/2 served and forwarded, a real application answers the same.
     *
     * <p>The setting moves both hops together, so this exercises HTTP/2 end to end against the
     * application's own runtime rather than a stub. The comparison is against the direct leg over
     * HTTP/1.1, which is the point: the protocol is the gateway's business and the answer is the
     * application's.
     */
    @Test
    void anApplicationAnswersIdenticallyOverHttp2() throws Exception {
        for (String path : List.of("/apps/" + APP + "/api/items", "/apps/" + APP + "/users",
                "/apps/" + APP + "/api/export")) {
            Captured straight = capture(direct, get(path));
            Captured overHttp2 = captureOver(h2Front, get(path), HttpClient.Version.HTTP_2);

            assertThat(overHttp2.status).as("status of " + path).isEqualTo(straight.status);
            assertThat(overHttp2.digest).as("body of " + path).isEqualTo(straight.digest);
        }
    }

    // ---------------------------------------------------------------- machinery

    private record Captured(int status, Map<String, List<String>> headers, long length,
            String digest, String body) {
    }

    /**
     * One request, expressed independently of which port it is aimed at — the two legs differ only
     * in the base, so the description of the call has to outlive the URI.
     */
    private record Call(String method, String path, String body, Map<String, String> headers) {

        Call header(String name, String value) {
            Map<String, String> merged = new TreeMap<>(headers);
            merged.put(name, value);
            return new Call(method, path, body, merged);
        }
    }

    private static void assertSame(String path) throws Exception {
        assertSame(get(path));
    }

    /** Issues the call at both ports and asserts the answers match. */
    private static void assertSame(Call call) throws Exception {
        Captured straight = capture(direct, call);
        Captured relayed = capture(front, call);

        assertThat(relayed.status).as("status of " + call.path).isEqualTo(straight.status);
        assertThat(relayed.length).as("body length of " + call.path).isEqualTo(straight.length);
        assertThat(relayed.digest).as("body bytes of " + call.path).isEqualTo(straight.digest);
        assertThat(comparable(relayed.headers)).as("headers of " + call.path)
                .isEqualTo(comparable(straight.headers));
    }

    private static Call get(String path) {
        return new Call("GET", path, null, Map.of());
    }

    private static Call head(String path) {
        return new Call("HEAD", path, null, Map.of());
    }

    private static Call post(String path, String body) {
        return new Call("POST", path, body, Map.of("Content-Type", "application/json"));
    }

    /**
     * Sends the call and captures the answer without materializing the body — the large cases are
     * past a hundred megabytes, and holding two copies to compare them would measure the test's
     * heap rather than the gateway's transparency.
     */
    private static Captured capture(String base, Call call) throws Exception {
        return captureOver(base, call, HttpClient.Version.HTTP_1_1);
    }

    private static Captured captureOver(String base, Call call, HttpClient.Version version)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + call.path))
                // Pinned, because an unpinned comparison measures which protocol each leg
                // negotiated — the HTTP/2 pseudo-header on one side, the upgrade offer on the
                // other — rather than whether the answer survived the hop.
                .version(version)
                .method(call.method, call.body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(call.body));
        call.headers.forEach(builder::header);
        HttpRequest built = builder.build();
        try (HttpClient client = HttpClient.newBuilder().version(version).build()) {
            HttpResponse<InputStream> response = client.send(built,
                    HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = 0;
            StringBuilder head = new StringBuilder();
            byte[] buffer = new byte[64 * 1024];
            try (InputStream body = response.body()) {
                int read;
                while ((read = body.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    if (head.length() < 64 * 1024) {
                        head.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                    length += read;
                }
            }
            Map<String, List<String>> headers = new TreeMap<>();
            response.headers().map().forEach(
                    (name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
            return new Captured(response.statusCode(), headers, length,
                    HexFormat.of().formatHex(digest.digest()), head.toString());
        }
    }

    private static Map<String, List<String>> comparable(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new TreeMap<>(headers);
        copy.keySet().removeAll(NOT_COMPARED);
        return copy;
    }

    // ---------------------------------------------------------------- fixture

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table items (id serial primary key,"
                    + " name varchar(200) not null)");
            statement.execute("insert into items (name) values ('one'), ('two')");
        }
    }

    private static void installApp() throws IOException {
        Path appHome = installRoot.resolve(APP).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                tesseraql:
                  security:
                    mtls:
                      forwardedHeader: X-Client-Cert
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        route(appHome, "api/items", "get.yml", """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        sql(appHome, "api/items", "list.sql", "select id, name from items order by id\n");

        // A streaming export: no declared length, so the answer is chunked.
        route(appHome, "api/export", "get.yml", exportRoute("items.export", "export.sql"));
        sql(appHome, "api/export", "export.sql", "select id, name from items order by id\n");

        // The same, past the ceiling the relay used to abort at. Generated rather than stored:
        // a hundred-odd megabytes is the point of the case, not of the fixture.
        route(appHome, "api/export-large", "get.yml",
                exportRoute("items.export.large", "export-large.sql"));
        sql(appHome, "api/export-large", "export-large.sql",
                "select g as id, repeat('x', 100) as v from generate_series(1, 700000) g\n");

        // Reads a body far past the old buffer cap and reports how much of it arrived.
        route(appHome, "api/measure", "post.yml", """
                version: tesseraql/v1
                id: body.measure
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  blob:
                    type: string
                    required: true
                    writable: true
                steps:
                  - id: main
                    sql:
                      file: measure.sql
                      mode: query
                      params:
                        blob: body.blob
                response:
                  json:
                    status: 200
                    body:
                      data: steps.main.rows
                """);
        sql(appHome, "api/measure", "measure.sql",
                "select length(/*blob*/'x') as n\n");

        route(appHome, "api/go", "post.yml", """
                version: tesseraql/v1
                id: go.redirect
                kind: route
                recipe: command-json
                security:
                  auth: public
                response:
                  redirect:
                    location: /api/items
                """);

        // A cookie whose attributes have to survive the hop. Set here rather than by signing in:
        // the session cookie needs a seeded realm and a real login, and what is under test is the
        // relay of the attribute, not how the value was minted. An html response because only
        // that one carries `headers:` — JsonResponse has no such field, and ignores it in silence.
        route(appHome, "api/cookie", "get.yml", """
                version: tesseraql/v1
                id: cookie.set
                kind: route
                recipe: query-html
                security:
                  auth: public
                response:
                  html:
                    status: 200
                    template: cookie.html
                    shell: never
                    headers:
                      Set-Cookie: "tesseraql_sid=abc; Path=/; HttpOnly; SameSite=Lax"
                """);
        Files.writeString(appHome.resolve("templates/cookie.html"), "<p>ok</p>\n");

        new AppCatalog(installRoot).register(
                new InstalledApp(APP, "1.0.0", APP + "/1.0.0", List.of()));
    }

    private static String exportRoute(String id, String file) {
        return """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-export
                security:
                  auth: public
                export:
                  format: csv
                  filename: rows.csv
                sources:
                  main:
                    sql:
                      file: %s
                """.formatted(id, file);
    }

    private static void route(Path appHome, String dir, String name, String body)
            throws IOException {
        Path target = appHome.resolve("web").resolve(dir);
        Files.createDirectories(target);
        Files.writeString(target.resolve(name), body);
    }

    private static void sql(Path appHome, String dir, String name, String body)
            throws IOException {
        Files.writeString(appHome.resolve("web").resolve(dir).resolve(name), body);
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
