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
 * {@code /_tesseraql/topics} stream after commit, the rendered list view carries the htmx sse
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
                List.of(), List.of("ADMIN"), List.of(), Map.of()));
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
                .contains("sse-connect=\"/_tesseraql/topics?topics=orders.changed\"")
                .contains("hx-trigger=\"sse:orders.changed\"")
                .contains("hx-ext=\"sse\"")
                .contains("hx-get=\"/orders\"")
                .contains("hx-select=\"#orders-table\"");
    }

    /** A committed command's emit: lands as a named, data-free frame on the topic stream. */
    @Test
    void aCommittedCommandEmitsItsTopicToTheStream() throws Exception {
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/topics?topics=orders.changed"))
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

    /** A rolled-back command (validation failure) emits nothing. */
    @Test
    void aFailedCommandEmitsNothing() throws Exception {
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/topics?topics=orders.changed"))
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
                        + "/_tesseraql/topics?topics=orders.changed")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
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
                order by
                  o.id
                """);
        Files.writeString(orders.resolve("orders.view.yml"), """
                version: tesseraql/v1
                kind: view
                view: list
                title: Orders
                refreshOn: orders.changed
                """);
        Files.writeString(orders.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sql:
                  file: orders.sql
                response:
                  html:
                    view: orders.view.yml
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
