package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    static HttpServer upstream;
    static TesseraqlRuntime runtime;
    static Path appHome;
    static final java.util.List<String> seenAuthorizations = java.util.Collections
            .synchronizedList(new java.util.ArrayList<>());
    static final java.util.List<String> seenSearchRequests = java.util.Collections
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
        upstream.createContext("/v1/search", exchange -> {
            // Echo what the source posted, so the test can assert the method and the body
            // actually left the runtime.
            byte[] posted = exchange.getRequestBody().readAllBytes();
            seenSearchRequests.add(exchange.getRequestMethod() + " "
                    + new String(posted, StandardCharsets.UTF_8));
            byte[] body = ("{\"matches\":[{\"code\":\"JPY\",\"name\":\"yen\"}],"
                    + "\"echo\":" + new String(posted, StandardCharsets.UTF_8) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.createContext("/v1/due", exchange -> {
            byte[] body = """
                    {"items":[{"sku":"A-1","due":"2026/01/15","note":"{\\"lot\\":7}"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        appHome = prepareAppHome(upstream.getAddress().getPort());
        runtime = TesseraqlRuntime.start(appHome, 0);
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
        assertThat(body.get("rows").get(0).get("status").asString()).isEqualTo("PENDING");
        // select: rates picked the array; each element is one row.
        assertThat(body.get("fx")).hasSize(2);
        assertThat(body.get("fx").get(0).get("code").asString()).isEqualTo("JPY");
        // The object-shaped body remains addressable for scalar shaping.
        assertThat(body.get("base").asString()).isEqualTo("USD");
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

    /**
     * A command's {@code http:} source applies its {@code result:} declaration like a query's
     * (docs/audit-low-leads.md, the {@code result:} sweep): the sources a command fetches before
     * its transaction used to accept the key and drop it, so a declared date stayed the
     * partner's text and a declared json stayed a string.
     */
    @Test
    void aCommandsHttpSourceAppliesItsDeclaration() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/mark"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("due")).hasSize(1);
        assertThat(body.get("due").get(0).get("due").asString()).isEqualTo("2026-01-15");
        assertThat(body.get("due").get(0).get("note").get("lot").asInt()).isEqualTo(7);
    }

    /** A reference API keyed by a list: the read side is no longer GET-only. */
    @Test
    void aSourceMayPostAQueryBody() throws Exception {
        HttpResponse<String> response = get("/search");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("matches")).hasSize(1);
        assertThat(body.get("matches").get(0).get("name").asString()).isEqualTo("yen");
        // The method and the body reached the upstream, rather than being dropped.
        assertThat(seenSearchRequests).anyMatch(seen -> seen.startsWith("POST ")
                && seen.contains("JPY"));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
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
                sources:
                  main:
                    sql:
                      file: orders.sql
                  rates:
                    http:
                      url: http://localhost:%d/v1/rates
                      credential: fx-api
                      select: rates
                  meta:
                    http:
                      url: http://localhost:%d/v1/rates
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                      fx: rates.rows
                      base: meta.body.base
                """.formatted(upstreamPort, upstreamPort));
        Path search = target.resolve("web/search");
        Files.createDirectories(search);
        Files.writeString(search.resolve("search.sql"), "select 'JPY' as code\n");
        Files.writeString(search.resolve("get.yml"), """
                version: tesseraql/v1
                id: search.list
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: search.sql
                  matches:
                    http:
                      method: POST
                      url: http://localhost:%d/v1/search
                      body: main.rows
                      select: matches
                response:
                  json:
                    status: 200
                    body:
                      matches: matches.rows
                """.formatted(upstreamPort));
        Path mark = target.resolve("web/mark");
        Files.createDirectories(mark);
        Files.writeString(mark.resolve("mark.sql"),
                "update orders set status = 'MARKED' where id = 1\n");
        Files.writeString(mark.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.mark
                kind: route
                recipe: command-json
                sources:
                  partner:
                    http:
                      url: http://localhost:%d/v1/due
                      select: items
                    result:
                      due: { type: date, format: yyyy/MM/dd }
                      note: { type: json }
                steps:
                  - id: mark
                    sql:
                      file: mark.sql
                      mode: update
                response:
                  json:
                    status: 200
                    body:
                      due: partner.rows
                """.formatted(upstreamPort));
        Path degraded = target.resolve("web/degraded");
        Files.createDirectories(degraded);
        Files.writeString(degraded.resolve("degraded.sql"), "select 1 as id\n");
        Files.writeString(degraded.resolve("get.yml"), """
                version: tesseraql/v1
                id: degraded.list
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: degraded.sql
                  fx:
                    http:
                      url: http://localhost:1/v1/rates
                      connectTimeout: 1s
                      requestTimeout: 1s
                      onError: empty
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                      fx: fx.rows
                """);
        return target;
    }
}
