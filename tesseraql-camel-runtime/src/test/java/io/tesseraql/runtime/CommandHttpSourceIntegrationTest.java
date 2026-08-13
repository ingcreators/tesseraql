package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A command's {@code http:} sources (docs/lookups.md, decision 19): the value a write depends
 * on is fetched <em>before</em> the transaction opens, so the write never waits on a partner,
 * and a failed fetch leaves no row behind.
 */
@Testcontainers
class CommandHttpSourceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static HttpServer upstream;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table orders (id serial primary key,"
                    + " partner_code varchar(8) not null, partner_name varchar(32) not null)");
        }
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/partner", exchange -> {
            byte[] body = "{\"name\":\"Acme from CRM\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        appHome = prepareAppHome(upstream.getAddress().getPort());
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
        if (appHome != null) {
            try (var files = Files.walk(appHome)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The fetched name is written by the command's own step, in its transaction. */
    @Test
    void aCommandWritesAValueItFetchedOverHttp() throws Exception {
        HttpResponse<String> response = post("/api/orders", "{\"partnerCode\":\"P1\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedName("P1")).isEqualTo("Acme from CRM");
    }

    /** A failed fetch fails the command before a row is written — not after. */
    @Test
    void aFailedFetchLeavesNoRowBehind() throws Exception {
        HttpResponse<String> response = post("/api/orders/unreachable", "{\"partnerCode\":\"P9\"}");
        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(storedName("P9")).isNull();
    }

    private static String storedName(String code) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "select partner_name from orders where partner_code = ?")) {
            statement.setString(1, code);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome(int upstreamPort) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-command-http-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: command-http-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  http:
                    outbound:
                      allowedHosts:
                        - localhost
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        writeCommand(target, "orders", "orders.create", upstreamPort);
        writeCommand(target, "orders/unreachable", "orders.unreachable", 1);
        return target;
    }

    private static void writeCommand(Path target, String path, String id, int port)
            throws IOException {
        Path dir = target.resolve("web/api/" + path);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("insert.sql"), """
                insert into orders (partner_code, partner_name)
                values (/* partnerCode */'P1', /* partnerName */'x')
                """);
        Files.writeString(dir.resolve("post.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: command-json
                input:
                  partnerCode: { type: string, required: true }
                steps:
                  - id: header
                    sql:
                      file: insert.sql
                      params:
                        partnerCode: body.partnerCode
                        partnerName: partner.body.name
                sources:
                  partner:
                    http:
                      url: http://localhost:%d/partner
                      readOnly: true
                      connectTimeout: 1s
                      requestTimeout: 1s
                response:
                  json:
                    status: 201
                    body:
                      ok: true
                """.formatted(id, port));
    }
}
