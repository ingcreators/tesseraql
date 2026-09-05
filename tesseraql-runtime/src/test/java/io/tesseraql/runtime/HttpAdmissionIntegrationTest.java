package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * The runtime-wide in-flight bound (docs/http-threading.md decision 3).
 *
 * <p>There was none: requests arriving while every worker was blocked in JDBC queued in Vert.x's
 * blocked-task queue, which has no bound. Beyond the bound the answer is now an immediate 503 that
 * a caller can retry, rather than a place in an invisible queue.
 */
@Testcontainers
class HttpAdmissionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    /** An event stream authenticates before it opens, so the stream cases need a session. */
    static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        sessionCookie = sessionCookieFor(runtime);
    }

    static String sessionCookieFor(TesseraqlRuntime target) {
        io.tesseraql.security.session.SessionStore sessions = target.context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.SESSION_STORE_BEAN,
                io.tesseraql.security.session.SessionStore.class);
        String sid = sessions.create(new io.tesseraql.security.Principal("admission-user",
                "admission-user", "Admission User", null, List.of(), List.of("ADMIN"),
                List.of(), java.util.Map.of()),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
        return sessions.cookieName() + "=" + sid;
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

    /**
     * One worker, two in flight: the third request is refused rather than queued.
     *
     * <p>The refusal carries {@code Retry-After} and the code, because an unexplained 503 reads as
     * broken where a bounded one reads as busy.
     */
    @Test
    void aRequestBeyondTheBoundIsRefusedRatherThanQueued() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        HttpResponse<String> refused = get("/api/nap");

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).contains("1");
        assertThat(refused.body()).contains("TQL-RATE-4293");
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * Health is not refused while the runtime is at its bound.
     *
     * <p>It is checked before the permit: health is the one surface whose whole purpose is to be
     * answerable when nothing else is, and a runtime killed for failing to say "I am busy" has
     * turned a slowdown into an outage.
     *
     * <p><strong>And it is prompt, not merely admitted.</strong> Health was a Camel route, so
     * being let past the gate still left it queueing for a worker — bounded by {@code maxInFlight}
     * rather than unbounded, which was an improvement and not an answer. It is now answered on
     * the router, so the elapsed time here is a real assertion rather than a hopeful one.
     */
    @Test
    void healthIsNotRefusedWhileTheRuntimeIsAtItsBound() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        // Proves the gate is saturated for ordinary traffic at the moment health is asked.
        assertThat(get("/api/nap").statusCode()).isEqualTo(503);
        long startedAt = System.currentTimeMillis();
        HttpResponse<String> health = get("/_tesseraql/health/live");
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        // The one worker is inside pg_sleep for a second; this did not wait for it.
        assertThat(elapsedMs).isLessThan(500);
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * A dot segment does not buy a request its way past the gate.
     *
     * <p>The gate exempts the health and asset mounts, and it read the target as transmitted while
     * vertx-web routes on the normalized path. {@code /_tesseraql/health/../../api/nap} therefore
     * satisfied the carve-out and was then routed to {@code /api/nap}: the whole in-flight bound
     * was one dot segment away from not existing.
     *
     * <p>Driven over a raw socket on purpose. {@link java.net.http.HttpClient} normalizes a
     * request target before it leaves, so the same case built on it would send {@code /api/nap}
     * and pass against the defect.
     */
    @Test
    void aDotSegmentPastAnExemptMountStillSpendsAPermit() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        String pastHealth = raw("/_tesseraql/health/../../api/nap");
        String pastAssets = raw("/assets/../api/nap");

        assertThat(pastHealth).contains("503").contains("TQL-RATE-4293");
        assertThat(pastAssets).contains("503").contains("TQL-RATE-4293");
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * The carve-out follows the router, so a spelling the router accepts is exempt too.
     *
     * <p>{@code %68} is an unreserved escape: vertx-web decodes it before matching, so this
     * request reaches the health route. Testing the transmitted target instead refused it while
     * the runtime was busy — the carve-out and the routing disagreed in both directions.
     */
    @Test
    void aPercentEncodedSpellingOfTheHealthMountIsStillExempt() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> saturating = List.of(
                CompletableFuture.supplyAsync(() -> get("/api/nap")),
                CompletableFuture.supplyAsync(() -> get("/api/nap")));
        awaitInFlight();

        String health = raw("/_tesseraql/%68ealth/live");

        assertThat(health).contains("200").contains("UP");
        for (CompletableFuture<HttpResponse<String>> request : saturating) {
            assertThat(request.get().statusCode()).isEqualTo(200);
        }
    }

    /**
     * A Unicode route is charged one permit, not two.
     *
     * <p>{@code UnicodePaths} reroutes such a request in its decoded form and routing restarts
     * from the first handler, so the gate's position behind it is what keeps the encoded spelling
     * and the decoded one from each taking a permit. One nap holds one of the two permits here;
     * a double charge would need both and answer 503.
     */
    @Test
    void aUnicodeRouteIsChargedOnePermit() throws Exception {
        CompletableFuture<HttpResponse<String>> saturating = CompletableFuture
                .supplyAsync(() -> get("/api/nap"));
        awaitInFlight();

        HttpResponse<String> unicode = get("/%E5%8F%97%E6%B3%A8");

        assertThat(unicode.statusCode()).isEqualTo(200);
        assertThat(saturating.get().statusCode()).isEqualTo(200);
    }

    /**
     * An open event stream does not stand in the budget ordinary routes are refused from.
     *
     * <p>A stream holds its routing context — and so its permit — for up to fifteen minutes, so
     * charging it to {@code maxInFlight} meant a handful of open live pages closed the runtime to
     * everything else. Here the two streams are the whole request budget; the business route must
     * still answer.
     */
    @Test
    void anEventStreamDoesNotSpendTheRequestBudget() throws Exception {
        try (OpenStreams open = openStreams(2)) {
            assertThat(open.opened()).isEqualTo(2);
            assertThat(get("/api/quick").statusCode()).isEqualTo(200);
        }
        // Closing the streams waits for the budget to come back, so reaching here is itself the
        // assertion that the permits were released rather than leaked.
    }

    /** Both saturating requests have taken their permits before the assertion runs. */
    private static void awaitInFlight() throws InterruptedException {
        Thread.sleep(700);
    }

    /**
     * Blocks until an event stream can be opened again, then closes it.
     *
     * <p>Asserts on eventual state rather than on elapsed time: a loaded runner decides how long
     * the server takes to notice a closed connection, and a fixed sleep here would be a flake
     * with a schedule.
     */
    private static void awaitStreamCapacity() {
        try {
            pollForStreamCapacity();
        } catch (IOException unreachable) {
            throw new IllegalStateException(unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void pollForStreamCapacity() throws IOException, InterruptedException {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(30);
        String lastBody = "";
        while (java.time.Instant.now().isBefore(deadline)) {
            // ofInputStream, never ofString: an event stream has no end, so a body handler that
            // waits for one would time out on the success case and never on the refusal.
            HttpResponse<java.io.InputStream> attempt = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                            + "/_tesseraql/events?topics=orders.changed"))
                            .header("Cookie", sessionCookie).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (attempt.statusCode() == 200) {
                attempt.body().close();
                return;
            }
            lastBody = new String(attempt.body().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            Thread.sleep(250);
        }
        throw new AssertionError("the stream permit never came back; last refusal: " + lastBody);
    }

    /** Opens {@code count} authenticated event streams and keeps them open until closed. */
    private static OpenStreams openStreams(int count) throws Exception {
        OpenStreams streams = new OpenStreams();
        for (int i = 0; i < count; i++) {
            streams.open("/_tesseraql/events?topics=orders.changed");
        }
        return streams;
    }

    /** A handful of open SSE connections, closed together. */
    static final class OpenStreams implements AutoCloseable {

        private final List<HttpResponse<java.io.InputStream>> responses = new java.util.ArrayList<>();

        void open(String path) throws Exception {
            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                            .header("Cookie", sessionCookie).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            // A refusal names which bound tripped, so a failure here is diagnosable rather than
            // just "not 200".
            assertThat(response.statusCode())
                    .as("opening stream #%d at %s: %s", responses.size() + 1, path,
                            response.statusCode() == 200 ? "" : bodyOf(response))
                    .isEqualTo(200);
            // Read the opening retry: frame, so the connection is established — and the permit
            // taken — before the assertion that depends on it runs.
            new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),
                    java.nio.charset.StandardCharsets.UTF_8)).readLine();
            responses.add(response);
        }

        int opened() {
            return responses.size();
        }

        private static String bodyOf(HttpResponse<java.io.InputStream> response) {
            try (java.io.InputStream body = response.body()) {
                return new String(body.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                return "<unreadable: " + unreadable + ">";
            }
        }

        @Override
        public void close() {
            for (HttpResponse<java.io.InputStream> response : responses) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // Closing a stream the server may already have ended.
                }
            }
            responses.clear();
            // Every test in this class shares one runtime, so leaving permits held would make
            // the next one fail for a reason that has nothing to do with it. The wait is a poll
            // on the budget rather than a sleep, because how long the server takes to notice a
            // closed connection is the runner's decision, not ours.
            awaitStreamCapacity();
        }
    }

    /**
     * Sends {@code target} exactly as written and returns the status line, headers and body.
     *
     * <p>The JDK client rewrites a target carrying dot segments before it reaches the wire, which
     * is the whole property under test here.
     */
    private static String raw(String target) {
        return rawFrom(runtime, target);
    }

    /** The same, against a runtime a nested fixture owns, with a session cookie attached. */
    private static String rawFrom(TesseraqlRuntime target, String path) {
        try (java.net.Socket socket = new java.net.Socket("localhost", target.port())) {
            socket.setSoTimeout(30_000);
            String cookie = target == runtime ? sessionCookie : sessionCookieFor(target);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: localhost\r\nCookie: " + cookie + "\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static HttpResponse<String> get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + path))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-http-admission-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: admission-it
                  http:
                    workerThreads: 1
                    maxInFlight: 2
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

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
        Files.writeString(nap.resolve("nap.sql"), "select pg_sleep(3) as nap\n");

        writeQuickRoute(target);
        writeEmittingRoute(target);

        // A Unicode route path, so UnicodePaths reroutes the request and routing restarts: the
        // gate must charge the decoded spelling once, not the encoded one as well.
        Path juchu = target.resolve("web/受注");
        Files.createDirectories(juchu);
        Files.writeString(juchu.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注一覧
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: 受注.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(juchu.resolve("受注.sql"), "select 1 as 受注番号\n");
        return target;
    }

    /** A route that answers immediately: the stream cases must not wait three seconds for it. */
    private static void writeQuickRoute(Path target) throws IOException {
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
    }

    /**
     * A route declaring {@code emit:}, which is what makes the runtime build the live registry
     * and register {@code /_tesseraql/events}. Without one there is no stream mount to bill.
     */
    private static void writeEmittingRoute(Path target) throws IOException {
        Path touch = target.resolve("web/api/touch");
        Files.createDirectories(touch);
        Files.writeString(touch.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.touch
                kind: route
                recipe: command-json
                emit: orders.changed
                security:
                  auth: browser
                  csrf: true
                steps:
                  - id: main
                    sql:
                      file: touch.sql
                      mode: update
                response:
                  json:
                    body:
                      ok: true
                """);
        Files.writeString(touch.resolve("touch.sql"),
                "update admission_touch set touched = touched + 1\n");
    }

    /**
     * The stream budget is its own number, not a share of the request budget.
     *
     * <p>The enclosing fixture proves the derived default — it declares no
     * {@code maxEventStreams}, so two streams saturate it exactly as {@code maxInFlight: 2}
     * does. This one declares the key at a different value from {@code maxInFlight}, which is
     * the only way to show that the key is read and that the two budgets are genuinely separate
     * numbers rather than one number consulted twice.
     *
     * <p>Its own runtime and app home, because both numbers are read once during boot; the
     * container is the enclosing class's.
     */
    @org.junit.jupiter.api.Nested
    class ADeclaredStreamBudget {

        @Test
        void isReadFromItsOwnKeyAndRefusesWithItsOwnCode() throws Exception {
            Path home = Files.createTempDirectory("tesseraql-stream-budget-it");
            Files.createDirectories(home.resolve("config"));
            Files.writeString(home.resolve("config/application.yml"), """
                    server:
                      port: 0

                    tesseraql:
                      app:
                        name: stream-budget-it
                      http:
                        workerThreads: 1
                        maxInFlight: 4
                        maxEventStreams: 1
                      datasources:
                        main:
                          jdbcUrl: %s
                          username: %s
                          password: %s
                    """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()));
            writeQuickRoute(home);
            writeEmittingRoute(home);

            TesseraqlRuntime declared = TesseraqlRuntime.start(home, 0);
            try {
                String cookie = sessionCookieFor(declared);
                HttpResponse<java.io.InputStream> first = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + declared.port()
                                + "/_tesseraql/events?topics=orders.changed"))
                                .header("Cookie", cookie).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertThat(first.statusCode()).isEqualTo(200);
                new java.io.BufferedReader(new java.io.InputStreamReader(first.body(),
                        java.nio.charset.StandardCharsets.UTF_8)).readLine();

                HttpResponse<String> second = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + declared.port()
                                + "/_tesseraql/events?topics=orders.changed"))
                                .header("Cookie", cookie).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(second.statusCode()).isEqualTo(503);
                assertThat(second.body()).contains("TQL-RATE-4295");
                assertThat(second.headers().firstValue("Retry-After")).contains("5");

                // The budget is selected on the path the router matched, so every spelling that
                // reaches the SSE handler is charged to it. Driven over a raw socket, because
                // the JDK client rewrites dot segments before they leave; and asserted here
                // rather than on the shared runtime, because a case that only opens
                // /_tesseraql/events would pass against a gate matching the raw target.
                assertThat(rawFrom(declared, "/_tesseraql/%65vents"))
                        .contains("503").contains("TQL-RATE-4295");
                assertThat(rawFrom(declared, "/_tesseraql/x/../events"))
                        .contains("503").contains("TQL-RATE-4295");
                // Trailing slash reaches the same handler, so it is the same budget.
                assertThat(rawFrom(declared, "/_tesseraql/events/"))
                        .contains("503").contains("TQL-RATE-4295");
                // maxInFlight is 4 and untouched by any of this.
                assertThat(HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + declared.port()
                                + "/api/quick")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                first.body().close();
            } finally {
                declared.close();
                try (java.util.stream.Stream<Path> files = Files.walk(home)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
    }
}
