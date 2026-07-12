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
 * Cross-node live views (docs/realtime.md): two runtimes of the same app share one PostgreSQL
 * main datasource; a command committed on node A rides {@code pg_notify} to node B's
 * {@link TopicNotifyBridge}, so a stream open on B receives the topic frame. Sessions live in
 * the shared {@code tql_session} table, so one browser session works against both nodes —
 * exactly the load-balanced deployment shape.
 */
@Testcontainers
class CrossNodeLiveViewIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime nodeA;
    static TesseraqlRuntime nodeB;
    static Path homeA;
    static Path homeB;
    static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table orders (id serial primary key, "
                    + "status varchar(32) not null)");
            statement.execute("insert into orders (status) values ('PENDING')");
        }
        homeA = prepareAppHome();
        homeB = prepareAppHome();
        nodeA = TesseraqlRuntime.start(homeA, freePort());
        nodeB = TesseraqlRuntime.start(homeB, freePort());
        SessionStore sessions = nodeA.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("cross-user", "cross-user", "Cross User",
                null, List.of(), List.of("ADMIN"), List.of(), Map.of()));
        sessionCookie = sessions.cookieName() + "=" + sid;
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : new TesseraqlRuntime[]{nodeA, nodeB}) {
            if (runtime != null) {
                runtime.close();
            }
        }
        for (Path home : new Path[]{homeA, homeB}) {
            if (home != null) {
                try (var files = Files.walk(home)) {
                    files.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> path.toFile().delete());
                }
            }
        }
    }

    /** A commit on node A lands as a named frame on a stream held open against node B. */
    @Test
    void aCommitOnOneNodeSignalsAStreamOnTheOther() throws Exception {
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + nodeB.port()
                        + "/_tesseraql/events?topics=orders.changed"))
                        .header("Cookie", sessionCookie).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        try (var frames = new java.io.BufferedReader(new java.io.InputStreamReader(
                stream.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            // The stream on B is open (retry frame on the wire) before A's command runs.
            assertThat(frames.readLine()).startsWith("retry:");
            assertThat(frames.readLine()).isEmpty();

            SessionStore sessions = nodeA.camelContext().getRegistry().lookupByNameAndType(
                    TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
            HttpResponse<String> commit = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + nodeA.port()
                            + "/orders/approve"))
                            .header("Cookie", sessionCookie)
                            .header("X-CSRF-Token", sessions.csrfTokenFromCookie(sessionCookie))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("status=APPROVED"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(commit.statusCode()).isEqualTo(200);

            // pg_notify crossed the database: B's bridge forwarded into its local hub.
            assertThat(frames.readLine()).isEqualTo("event: orders.changed");
            assertThat(frames.readLine()).isEqualTo("data: ");
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-cross-node-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: cross-node-it
                  sessions:
                    store: jdbc
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
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
