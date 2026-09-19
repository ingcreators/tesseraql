package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application half of the transfer scope, end to end (docs/edge-hygiene.md E0;
 * docs/audit-low-leads.md slice 22, EH-03): two applications sharing one database — a stack
 * whose members name the same URL, no per-application schema — carry the same public export
 * route id, and a transfer started under one is unknown to the other's subtree: status, file
 * and cancel each answer exactly as for an id that does not exist. The route half is
 * {@code TransferRouteScopeIntegrationTest}'s; this half was proven by a proxy-backed unit test
 * alone, on the belief that no harness could boot two applications against one table. Two
 * runtimes on one Testcontainers PostgreSQL is what the shared-session test has done all along.
 */
@Testcontainers
class TransferApplicationScopeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime shop;
    static TesseraqlRuntime warehouse;
    static Path shopHome;
    static Path warehouseHome;

    @BeforeAll
    static void start() throws Exception {
        shopHome = prepareAppHome("shop");
        warehouseHome = prepareAppHome("warehouse");
        shop = TesseraqlRuntime.start(shopHome, 0);
        warehouse = TesseraqlRuntime.start(warehouseHome, 0);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("insert into orders (order_no) values ('o-1'), ('o-2')");
        }
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : new TesseraqlRuntime[]{shop, warehouse}) {
            if (runtime != null) {
                runtime.close();
            }
        }
        for (Path home : new Path[]{shopHome, warehouseHome}) {
            if (home != null) {
                deleteRecursively(home);
            }
        }
    }

    @Test
    void anotherApplicationsSubtreeAnswersATransferAsUnknown() throws Exception {
        String transferId = startTransfer(shop, "/api/orders/export-public");
        JsonNode own = awaitTerminal(shop, "/api/orders/export-public/" + transferId);
        assertThat(own.get("status").asText()).isEqualTo("COMPLETED");

        // The same route id, the same table, another application: the bytes first.
        HttpResponse<String> file = get(warehouse,
                "/api/orders/export-public/" + transferId + "/file");
        assertThat(file.statusCode()).as("the other application's GET of the file: %s",
                file.body()).isEqualTo(404);
        assertThat(file.body()).doesNotContain("order_no").contains("TQL-LD-2822");
        HttpResponse<String> status = get(warehouse, "/api/orders/export-public/" + transferId);
        assertThat(status.statusCode()).isEqualTo(404);
        assertThat(status.body()).contains("TQL-LD-2822");
        HttpResponse<String> cancel = post(warehouse,
                "/api/orders/export-public/" + transferId + "/cancel");
        assertThat(cancel.statusCode()).isEqualTo(404);

        // No existence oracle: the foreign answer is the unknown-id answer, id factored out.
        HttpResponse<String> unknown = get(warehouse, "/api/orders/export-public/no-such-transfer");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(status.body().replace(transferId, "no-such-transfer"))
                .isEqualTo(unknown.body());

        // Its own application still serves it, bytes included.
        HttpResponse<String> ownFile = get(shop,
                "/api/orders/export-public/" + transferId + "/file");
        assertThat(ownFile.statusCode()).isEqualTo(200);
        assertThat(ownFile.body()).contains("order_no").contains("o-1");
    }

    private static String startTransfer(TesseraqlRuntime runtime, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asText();
    }

    private static JsonNode awaitTerminal(TesseraqlRuntime runtime, String statusPath)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            JsonNode status = MAPPER.readTree(get(runtime, statusPath).body());
            String value = status.get("status").asText();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static HttpResponse<String> get(TesseraqlRuntime runtime, String path)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(TesseraqlRuntime runtime, String path)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * One application home named {@code name}: the shared URL, no {@code currentSchema}, one
     * public export route. The table is created once — the second application's migration
     * finds it (each application keeps its own schema history; the framework tables are one).
     */
    private static Path prepareAppHome(String name) throws IOException {
        Path home = Files.createTempDirectory("tesseraql-transfer-app-scope-" + name);
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(name, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__orders.sql"),
                "create table if not exists orders (order_no varchar(64) primary key);\n");
        Path route = home.resolve("web/api/orders/export-public");
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.exportPublic
                kind: route
                recipe: file-export
                security:
                  auth: public
                export:
                  format: csv
                  filename: orders.csv
                sources:
                  main:
                    sql:
                      file: select-orders.sql
                """);
        Files.writeString(route.resolve("select-orders.sql"),
                "select order_no from orders order by order_no\n;\n");
        return home;
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
