package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Declarative HTTP caching end to end (docs/response-shaping.md, "HTTP caching"): a query
 * route's {@code cache:} block stamps {@code Cache-Control}, hashes the rendered body into a
 * strong {@code ETag}, and answers a matching conditional GET with {@code 304} and no body.
 */
@Testcontainers
class HttpCacheIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table orders (id serial primary key, "
                    + "status varchar(32) not null)");
            statement.execute("insert into orders (status) values ('PENDING')");
        }
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
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

    @Test
    void stampsCacheControlAndAnswersConditionalGetsWith304() throws Exception {
        HttpResponse<String> first = get("/orders", null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control"))
                .contains("public, max-age=30, stale-while-revalidate=60");
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("\"").endsWith("\"");

        // The same content revalidates to 304 with no body; the tag survives the response.
        HttpResponse<String> revalidated = get("/orders", etag);
        assertThat(revalidated.statusCode()).isEqualTo(304);
        assertThat(revalidated.body()).isEmpty();
        assertThat(revalidated.headers().firstValue("ETag")).contains(etag);

        // Changed data changes the tag, so a stale validator gets fresh content.
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("update orders set status = 'APPROVED' where id = 1");
        }
        HttpResponse<String> changed = get("/orders", etag);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.body()).contains("APPROVED");
        assertThat(changed.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
    }

    private static HttpResponse<String> get(String path, String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-http-cache-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: http-cache-it
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
        Files.writeString(orders.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                cache:
                  maxAge: 30s
                  visibility: public
                  staleWhileRevalidate: 60s
                sql:
                  file: orders.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """);
        return target;
    }
}
