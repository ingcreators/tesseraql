package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live views end to end (docs/realtime.md): a command route's {@code emit:} signals the
 * {@code /_tesseraql/events} stream after commit, the rendered list view carries the htmx sse
 * wiring for its {@code refreshOn:} topic, and the stream is session-authenticated and
 * data-free (named events only).
 */
@Testcontainers
class LiveViewIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table orders (id serial primary key, "
                    + "status varchar(32) not null)");
            statement.execute("insert into orders (status) values ('PENDING')");
        }
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("live-user", "live-user", "Live User", null,
                List.of(), List.of("ADMIN"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
        sessionCookie = sessions.cookieName() + "=" + sid;
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (var files = Files.walk(appHome)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The rendered list carries the sse wiring for its refreshOn: topic. */
    @Test
    void theListViewRendersTheLiveRefreshWiring() throws Exception {
        HttpResponse<String> page = get("/orders");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body())
                .contains("sse-connect=\"/_tesseraql/events?topics=orders.changed\"")
                .contains("hx-trigger=\"sse:orders.changed\"")
                .contains("hx-ext=\"sse\"")
                .contains("hx-get=\"/orders\"")
                .contains("hx-select=\"#orders-table\"")
                // The refetch carries the live client state: the typed search term (the
                // #orders-search input, outside the swapped region) and the sort/dir inputs.
                .contains(
                        "hx-include=\"#orders-table input[type=&#39;hidden&#39;], #orders-search\"")
                .contains("id=\"orders-search\"");
    }

    /** Detail and dashboard views carry the same wiring on their <id>-view region. */
    @Test
    void detailAndDashboardViewsRenderTheLiveRefreshWiring() throws Exception {
        HttpResponse<String> detail = get("/orders/1");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
                .contains("id=\"order-view\"")
                .contains("sse-connect=\"/_tesseraql/events?topics=orders.changed\"")
                .contains("hx-trigger=\"sse:orders.changed\"")
                .contains("hx-get=\"/orders/1\"")
                .contains("hx-select=\"#order-view\"");

        HttpResponse<String> dashboard = get("/orders/stats");
        assertThat(dashboard.statusCode()).isEqualTo(200);
        assertThat(dashboard.body())
                .contains("id=\"stats-view\"")
                .contains("hx-trigger=\"sse:orders.changed\"")
                .contains("hx-get=\"/orders/stats\"")
                .contains("hx-select=\"#stats-view\"");
    }

    /** A committed command's emit: lands as a named, data-free frame on the topic stream. */
    @Test
    void aCommittedCommandEmitsItsTopicToTheStream() throws Exception {
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/events?topics=orders.changed"))
                        .header("Cookie", sessionCookie).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");
        try (var frames = new java.io.BufferedReader(new java.io.InputStreamReader(
                stream.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            // The stream opens with the reconnect delay: subscribed before the command runs.
            assertThat(frames.readLine()).startsWith("retry:");
            assertThat(frames.readLine()).isEmpty();

            assertThat(postCommand("status=APPROVED").statusCode()).isEqualTo(200);

            assertThat(frames.readLine()).isEqualTo("event: orders.changed");
            assertThat(frames.readLine()).isEqualTo("data: ");
        }
    }

    /**
     * An invalidated session ends an already-open stream.
     *
     * <p>It did not. The stream authenticated once at connect and never looked again, so "sign out
     * others" and a password change left an open stream delivering data for up to its fifteen
     * minute lifetime — against security-hardening.md's claim that a credential change evicts a
     * parallel session. The claim was the right behaviour; the code was the part that was wrong.
     */
    @Test
    void invalidatingTheSessionEndsAnOpenStream() throws Exception {
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("evicted", "evicted", "Evicted", null,
                List.of(), List.of("ADMIN"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
        String cookie = sessions.cookieName() + "=" + sid;

        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/events?topics=orders.changed"))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);

        try (var frames = new java.io.BufferedReader(new java.io.InputStreamReader(
                stream.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            assertThat(frames.readLine()).startsWith("retry:");
            assertThat(frames.readLine()).isEmpty();

            sessions.invalidate(sid);

            // The next frame is what notices: the write re-checks the session, finds it gone and
            // closes the stream, so the reader sees end-of-stream rather than the event.
            assertThat(postCommand("status=APPROVED").statusCode()).isEqualTo(200);
            assertThat(frames.readLine()).isNull();
        }
    }

    /** A rolled-back command (validation failure) emits nothing. */
    @Test
    void aFailedCommandEmitsNothing() throws Exception {
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/events?topics=orders.changed"))
                        .header("Cookie", sessionCookie).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var frames = new java.io.BufferedReader(new java.io.InputStreamReader(
                stream.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            assertThat(frames.readLine()).startsWith("retry:");
            assertThat(frames.readLine()).isEmpty();

            // Rejected by the input enum before any SQL runs — the transaction never commits.
            assertThat(postCommand("status=NOT-A-STATUS").statusCode()).isEqualTo(400);

            // The next frame is the heartbeat ping, not the topic (bounded wait via the
            // stream's own heartbeat; a wrongly-emitted topic would arrive first).
            assertThat(frames.readLine()).isEqualTo("event: ping");
        }
    }

    /** The topic stream rides the browser session: anonymous connections are refused. */
    @Test
    void theTopicStreamRequiresASession() throws Exception {
        HttpResponse<String> refused = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/events?topics=orders.changed")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
    }

    /**
     * A topic no route declares with {@code emit:} is refused before the stream opens. It used
     * to be filtered out silently, so a typo opened a perfectly healthy-looking stream — 200,
     * event-stream, heartbeats forever — that could never fire, and the page waited for a
     * refresh signal that was not coming (docs/silent-tolerance.md O10).
     */
    @Test
    void anUndeclaredTopicIsRefusedRatherThanOpeningADeadStream() throws Exception {
        HttpResponse<String> refused = get("/_tesseraql/events?topics=odrers.changed");

        // The envelope carries the code only — this transport deliberately never concatenates
        // the exception text into its JSON — so the code is what names the problem class.
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("TQL-VIEW-3320");
        assertThat(refused.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        // The declared spelling opens a stream instead — covered by the delivery test above,
        // which reads it as a stream; reading a live stream as a string would never return.
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", sessionCookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postCommand(String form) throws Exception {
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/orders/approve"))
                .header("Cookie", sessionCookie)
                .header("X-CSRF-Token", sessions.csrfTokenFromCookie(sessionCookie))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-live-view-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: live-view-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path orders = target.resolve("web/orders");
        Files.createDirectories(orders);
        Files.writeString(orders.resolve("orders.sql"), """
                select
                  o.id,
                  o.status
                from
                  orders o
                where
                  1 = 1
                /*%if q != null && q != "" */
                  and o.status like /* q */ 'PENDING'
                /*%end*/
                order by
                  o.id
                """);
        Files.writeString(orders.resolve("orders.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Orders
                search: q
                refreshOn: orders.changed
                """);
        Files.writeString(orders.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sources:
                  main:
                    sql:
                      file: orders.sql
                response:
                  html:
                    view: orders
                """);
        Files.writeString(orders.resolve("order.sql"), """
                select
                  o.id,
                  o.status
                from
                  orders o
                where
                  o.id = /* id */ 1
                """);
        Files.writeString(orders.resolve("order.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: detail
                title: Order
                refreshOn: orders.changed
                """);
        Path detail = target.resolve("web/orders/{id}");
        Files.createDirectories(detail);
        Files.writeString(detail.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.detail
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sources:
                  main:
                    sql:
                      file: ../order.sql
                response:
                  html:
                    view: order
                """);
        Files.writeString(orders.resolve("stats.sql"), """
                select
                  count(*) as order_count
                from
                  orders
                """);
        Files.writeString(orders.resolve("stats.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                title: Order stats
                refreshOn: orders.changed
                panels:
                  - type: stat
                    source: sql
                    column: order_count
                """);
        Path stats = target.resolve("web/orders/stats");
        Files.createDirectories(stats);
        Files.writeString(stats.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.stats
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sources:
                  main:
                    sql:
                      file: ../stats.sql
                response:
                  html:
                    view: stats
                """);
        Path approve = target.resolve("web/orders/approve");
        Files.createDirectories(approve);
        Files.writeString(approve.resolve("approve.sql"), """
                update
                  orders
                set
                  status = /* status */ 'APPROVED'
                where
                  id = 1
                """);
        Files.writeString(approve.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.approve
                kind: route
                recipe: command-json
                emit: orders.changed
                input:
                  status:
                    type: string
                    required: true
                    enum: [APPROVED, DENIED]
                security:
                  auth: browser
                  csrf: true
                steps:
                  main:
                    sql:
                      file: approve.sql
                      mode: update
                      params:
                        status: body.status
                response:
                  json:
                    status: 200
                    body:
                      affected: sql.affectedRows
                """);
        return target;
    }
}
