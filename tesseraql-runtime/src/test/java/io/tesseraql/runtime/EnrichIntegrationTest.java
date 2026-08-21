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
 * enrich: end to end (docs/lookups.md): a row set carrying a code is enriched with the name
 * behind it, by key rather than by row — including a composite key, a named query as the
 * target, and a batch size small enough that the key set is split across several statements.
 */
@Testcontainers
class EnrichIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static HttpServer upstream;
    static final java.util.List<String> seenRequests = java.util.Collections
            .synchronizedList(new java.util.ArrayList<>());

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table orders (id serial primary key,"
                    + " partner_code varchar(8) not null, buyer_code varchar(8) not null)");
            statement.execute("create table partners (code varchar(8) primary key,"
                    + " name varchar(32) not null)");
            statement.execute("create table contracts (buyer varchar(8) not null,"
                    + " supplier varchar(8) not null, terms varchar(32) not null,"
                    + " primary key (buyer, supplier))");
            statement.execute("create table history (order_id integer not null,"
                    + " partner_code varchar(8) not null, note varchar(32) not null)");
            // Four orders over three distinct partners: the fourth costs no extra lookup, and
            // P9 has no partner row at all.
            statement.execute("insert into orders (partner_code, buyer_code) values"
                    + " ('P1', 'B1'), ('P2', 'B1'), ('P1', 'B2'), ('P9', 'B1')");
            statement.execute("insert into partners (code, name) values"
                    + " ('P1', 'Acme'), ('P2', 'Globex')");
            statement.execute("insert into contracts (buyer, supplier, terms) values"
                    + " ('B1', 'P1', 'net30'), ('B1', 'P2', 'net60'), ('B2', 'P1', 'prepaid')");
            statement.execute("insert into history (order_id, partner_code, note) values"
                    + " (1, 'P1', 'created'), (1, 'P2', 'reassigned')");
        }
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // One resource per key: GET /partners/{code}. The path is what the enrichment keys.
        upstream.createContext("/partners/", exchange -> {
            seenRequests.add(exchange.getRequestURI().getRawPath());
            String code = exchange.getRequestURI().getPath()
                    .substring("/partners/".length());
            byte[] body = ("{\"name\":\"http-" + code + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // One request for the whole key set: POST /search with the keys as the body.
        upstream.createContext("/search", exchange -> {
            String posted = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            seenRequests.add("POST /search " + posted);
            StringBuilder matches = new StringBuilder("{\"matches\":[");
            for (String code : new String[]{"P1", "P2"}) {
                if (posted.contains(code)) {
                    matches.append(matches.length() > 13 ? "," : "")
                            .append("{\"code\":\"").append(code)
                            .append("\",\"name\":\"batch-").append(code).append("\"}");
                }
            }
            byte[] body = matches.append("]}").toString().getBytes(StandardCharsets.UTF_8);
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

    /** The name behind the code lands on the row itself, and an unmatched code stays shaped. */
    @Test
    void mergesTheReferenceNameOntoEveryRow() throws Exception {
        JsonNode rows = MAPPER.readTree(get("/api/orders").body()).get("rows");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("partner_name").asText()).isEqualTo("Acme");
        assertThat(rows.get(1).get("partner_name").asText()).isEqualTo("Globex");
        assertThat(rows.get(2).get("partner_name").asText()).isEqualTo("Acme");
        // No partner row for P9: the column is present and null, not absent.
        assertThat(rows.get(3).has("partner_name")).isTrue();
        assertThat(rows.get(3).get("partner_name").isNull()).isTrue();
    }

    /** An export folds the reference into the rows it writes, a window at a time. */
    @Test
    void anExportEnrichesTheRowsItWrites() throws Exception {
        String csv = get("/api/orders/report").body();
        // Two windows of two rows, one reference query each; every row still carries its name.
        assertThat(csv).contains("Acme").contains("Globex");
        // The unmatched key writes an empty cell rather than dropping the row.
        assertThat(csv.lines().filter(line -> !line.isBlank()).count()).isEqualTo(5);
    }

    /** A composite key joins on every column, from a reference on a second table. */
    @Test
    void aCompositeKeyEnrichesFromItsOwnReference() throws Exception {
        JsonNode rows = MAPPER.readTree(get("/api/orders").body()).get("rows");
        assertThat(rows.get(0).get("terms").asText()).isEqualTo("net30");
        assertThat(rows.get(1).get("terms").asText()).isEqualTo("net60");
        // Same buyer as row 0, same partner as row 1 — only the pair picks 'prepaid'.
        assertThat(rows.get(2).get("terms").asText()).isEqualTo("prepaid");
    }

    /** into: names a named query, so a detail page's history is enriched the same way. */
    @Test
    void aNamedQueryIsEnrichedLikeTheMainResult() throws Exception {
        JsonNode body = MAPPER.readTree(get("/api/orders/1").body());
        assertThat(body.get("history")).hasSize(2);
        assertThat(body.get("history").get(0).get("partner_name").asText()).isEqualTo("Acme");
        assertThat(body.get("history").get(1).get("partner_name").asText()).isEqualTo("Globex");
    }

    /** batchSize: 1 splits the key set across statements; the merged result is the same. */
    @Test
    void aKeySetLargerThanTheBatchIsSplitAndStillMergesWhole() throws Exception {
        JsonNode rows = MAPPER.readTree(get("/api/orders/batched").body()).get("rows");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("partner_name").asText()).isEqualTo("Acme");
        assertThat(rows.get(1).get("partner_name").asText()).isEqualTo("Globex");
        assertThat(rows.get(3).get("partner_name").isNull()).isTrue();
    }

    /** maxKeys: is a ceiling on the fan-out, not a cap that quietly enriches part of the page. */
    @Test
    void aKeySetOverMaxKeysFailsRatherThanEnrichingSome() throws Exception {
        HttpResponse<String> response = get("/api/orders/capped");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("TQL-SQL-2114");
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

    /** perRow: one request per distinct key, keyed in the path. */
    @Test
    void aPerRowHttpReferenceCallsOncePerDistinctKey() throws Exception {
        seenRequests.clear();
        JsonNode rows = MAPPER.readTree(get("/api/orders/via-http").body()).get("rows");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("http-P1");
        assertThat(rows.get(1).get("name").asText()).isEqualTo("http-P2");
        // Row 2 repeats P1 and costs no second call; three distinct keys, three requests.
        assertThat(rows.get(2).get("name").asText()).isEqualTo("http-P1");
        assertThat(seenRequests.stream().filter(r -> r.startsWith("/partners/"))).hasSize(3);
    }

    /** batch: one request carries the whole key set. */
    @Test
    void aBatchHttpReferenceCallsOnceForTheWholeKeySet() throws Exception {
        seenRequests.clear();
        JsonNode rows = MAPPER.readTree(get("/api/orders/via-search").body()).get("rows");
        assertThat(rows.get(0).get("name").asText()).isEqualTo("batch-P1");
        assertThat(rows.get(1).get("name").asText()).isEqualTo("batch-P2");
        assertThat(rows.get(3).get("name").isNull()).isTrue();
        assertThat(seenRequests.stream().filter(r -> r.startsWith("POST /search"))).hasSize(1);
    }

    /** onError: empty degrades the whole enrichment, never a subset of the rows. */
    @Test
    void aDeadReferenceLeavesEveryRowUnenriched() throws Exception {
        JsonNode rows = MAPPER.readTree(get("/api/orders/degraded").body()).get("rows");
        assertThat(rows).hasSize(4);
        assertThat(rows).allMatch(row -> row.get("name").isNull());
    }

    private static Path prepareAppHome(int upstreamPort) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-enrich-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: enrich-it
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

        Path orders = target.resolve("web/api/orders");
        Files.createDirectories(orders);
        Files.writeString(orders.resolve("orders.sql"),
                "select id, partner_code, buyer_code from orders order by id\n");
        Files.writeString(orders.resolve("partners.sql"), """
                select code, name as partner_name
                from partners
                where code in /* keys */('P1', 'P2')
                """);
        Files.writeString(orders.resolve("contracts.sql"), """
                select buyer, supplier, terms
                from contracts
                where
                /*%for k : keys separator ' or ' */
                  (buyer = /* k.buyer */'B1' and supplier = /* k.supplier */'P1')
                /*%end*/
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
                    enrich:
                      partner:
                        on: { partner_code: code }
                        sql:
                          file: partners.sql
                        merge: [partner_name]
                      contract:
                        on: { buyer_code: buyer, partner_code: supplier }
                        sql:
                          file: contracts.sql
                        merge: [terms]
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                """);

        // An export enriches the rows it is writing, a window at a time (slice 13b). batchSize
        // is 2 against 4 rows, so the windowing is exercised rather than incidental.
        Path report = target.resolve("web/api/orders/report");
        Files.createDirectories(report);
        Files.writeString(report.resolve("rows.sql"),
                "select id, partner_code from orders order by id\n");
        Files.writeString(report.resolve("partners.sql"), """
                select code, name as partner_name
                from partners
                where code in /* keys */('P1', 'P2')
                """);
        Files.writeString(report.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.report
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: rows.sql
                      mode: query-export
                    enrich:
                      partner:
                        on: { partner_code: code }
                        sql:
                          file: partners.sql
                        batchSize: 2
                        merge: [partner_name]
                export:
                  format: csv
                  filename: orders.csv
                  locale: en
                  columns:
                    - name: id
                    - name: partner_name
                """);

        Path detail = target.resolve("web/api/orders/{id}");
        Files.createDirectories(detail);
        Files.writeString(detail.resolve("order.sql"),
                "select id, partner_code from orders where id = /* id */1\n");
        Files.writeString(detail.resolve("history.sql"), """
                select order_id, partner_code, note
                from history
                where order_id = /* id */1
                order by note
                """);
        Files.writeString(detail.resolve("partners.sql"), """
                select code, name as partner_name
                from partners
                where code in /* keys */('P1')
                """);
        Files.writeString(detail.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.detail
                kind: route
                recipe: query-json
                input:
                  id: { type: integer, required: true }
                sources:
                  main:
                    sql:
                      file: order.sql
                      params: { id: path.id }
                  history:
                    sql:
                      file: history.sql
                      params: { id: path.id }
                    enrich:
                      historyPartner:
                        on: { partner_code: code }
                        sql:
                          file: partners.sql
                        merge: [partner_name]
                response:
                  json:
                    status: 200
                    body:
                      order: main.rows
                      history: history.rows
                """);

        writeVariant(target, "batched", "batchSize: 1");
        writeVariant(target, "capped", "maxKeys: 1");
        writeHttpVariant(target, "via-http", """
                http:
                          url: http://localhost:%d/partners/{key.code}
                        merge: [name]""".formatted(upstreamPort), "name");
        writeHttpVariant(target, "via-search", """
                http:
                          method: POST
                          url: http://localhost:%d/search
                          body: keys
                          select: matches
                        mode: batch
                        merge: [name]""".formatted(upstreamPort), "name");
        writeHttpVariant(target, "degraded", """
                http:
                          url: http://localhost:1/partners/{key.code}
                          connectTimeout: 1s
                          requestTimeout: 1s
                          onError: empty
                        merge: [name]""", "name");
        return target;
    }

    /** The orders list enriched over HTTP; {@code reference} is the whole difference. */
    private static void writeHttpVariant(Path target, String name, String reference,
            String merged) throws IOException {
        Path dir = target.resolve("web/api/orders/" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("orders.sql"),
                "select id, partner_code from orders order by id\n");
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.%s
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: orders.sql
                    enrich:
                      partner:
                        on: { partner_code: code }
                        %s
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                """.formatted(name.replace('-', '.'), reference));
    }

    /** The orders list again, with one bound overridden — the whole difference under test. */
    private static void writeVariant(Path target, String name, String bound) throws IOException {
        Path dir = target.resolve("web/api/orders/" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("orders.sql"),
                "select id, partner_code from orders order by id\n");
        Files.writeString(dir.resolve("partners.sql"), """
                select code, name as partner_name
                from partners
                where code in /* keys */('P1')
                """);
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.%s
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: orders.sql
                    enrich:
                      partner:
                        on: { partner_code: code }
                        sql:
                          file: partners.sql
                        merge: [partner_name]
                        %s
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                """.formatted(name, bound));
    }
}
