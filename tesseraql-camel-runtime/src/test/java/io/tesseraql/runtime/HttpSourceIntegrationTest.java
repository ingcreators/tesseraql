package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * http: sources end to end (docs/connectors.md, "HTTP sources"): a query route composes an
 * external JSON API with its SQL result in one response, the source rides the outbound
 * gateway (allow-list, credential header), and {@code onError: empty} degrades a dead
 * upstream to zero rows instead of failing the page.
 */
@Testcontainers
class HttpSourceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final ObjectMapper MAPPER = new ObjectMapper();

    static HttpServer upstream;
    static TesseraqlRuntime runtime;
    static Path appHome;
    static final java.util.List<String> seenAuthorizations = java.util.Collections
            .synchronizedList(new java.util.ArrayList<>());

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table orders (id serial primary key, "
                    + "status varchar(32) not null)");
            statement.execute("insert into orders (status) values ('PENDING')");
        }
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/v1/rates", exchange -> {
            seenAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"base":"USD","rates":[{"code":"JPY","value":150.1},
                     {"code":"EUR","value":0.9}]}
                    """.getBytes(StandardCharsets.UTF_8);
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

    /** SQL rows and the selected API rows compose in one JSON response. */
    @Test
    void composesSqlAndApiResultsInOneResponse() throws Exception {
        HttpResponse<String> response = get("/orders");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("rows")).hasSize(1);
        assertThat(body.get("rows").get(0).get("status").asText()).isEqualTo("PENDING");
        // select: rates picked the array; each element is one row.
        assertThat(body.get("fx")).hasSize(2);
        assertThat(body.get("fx").get(0).get("code").asText()).isEqualTo("JPY");
        // The object-shaped body remains addressable for scalar shaping.
        assertThat(body.get("base").asText()).isEqualTo("USD");
        // The named credential rode the outbound gateway onto the rates request (the
        // credential-less meta source sends none).
        assertThat(seenAuthorizations).contains("Bearer fx-dummy-token");
    }

    /** onError: empty — a dead upstream degrades to zero rows; the page still renders. */
    @Test
    void aDeadUpstreamDegradesToEmptyInsteadOfFailing() throws Exception {
        HttpResponse<String> response = get("/degraded");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("rows")).hasSize(1);
        assertThat(body.get("fx")).isEmpty();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome(int upstreamPort) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-http-source-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: http-source-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  http:
                    outbound:
                      allowedHosts:
                        - localhost
                      credentials:
                        fx-api:
                          type: bearer
                          token: fx-dummy-token
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
                sql:
                  file: orders.sql
                http:
                  rates:
                    url: http://localhost:%d/v1/rates
                    credential: fx-api
                    select: rates
                  meta:
                    url: http://localhost:%d/v1/rates
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                      fx: rates.rows
                      base: meta.body.base
                """.formatted(upstreamPort, upstreamPort));
        Path degraded = target.resolve("web/degraded");
        Files.createDirectories(degraded);
        Files.writeString(degraded.resolve("degraded.sql"), "select 1 as id\n");
        Files.writeString(degraded.resolve("get.yml"), """
                version: tesseraql/v1
                id: degraded.list
                kind: route
                recipe: query-json
                sql:
                  file: degraded.sql
                http:
                  fx:
                    url: http://localhost:1/v1/rates
                    connectTimeout: 1s
                    requestTimeout: 1s
                    onError: empty
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                      fx: fx.rows
                """);
        return target;
    }
}
