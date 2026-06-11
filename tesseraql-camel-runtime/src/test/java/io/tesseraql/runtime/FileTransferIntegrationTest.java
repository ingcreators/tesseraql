package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end test for asynchronous file transfers (design ch. 28): a CSV upload imports rows
 * through a per-row 2-way statement (all-or-nothing by default, skip mode on request), and an
 * export streams a query into a downloadable CSV with the follow-up statement running either in
 * the extraction transaction or on first download.
 */
@Testcontainers
class FileTransferIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void importAppliesEveryRowAndReportsCompletion() throws Exception {
        String transferId = startTransfer("/api/items/import",
                "name,qty\nalpha,1\nbeta,2\n");
        JsonNode status = awaitTerminal("/api/items/import/" + transferId);

        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("rows").asLong()).isEqualTo(2);
        assertThat(itemCount("alpha")).isEqualTo(1);
        assertThat(itemCount("beta")).isEqualTo(1);
    }

    @Test
    void rejectedRowRollsTheWholeImportBackAndIsReported() throws Exception {
        String transferId = startTransfer("/api/items/import",
                "name,qty\ngamma,3\nbroken,not-a-number\n");
        JsonNode status = awaitTerminal("/api/items/import/" + transferId);

        assertThat(status.get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("errors")).isNotNull();
        assertThat(status.get("errors").get(0).get("row").asLong()).isEqualTo(2);
        // All-or-nothing: the clean first row was rolled back with the bad one.
        assertThat(itemCount("gamma")).isZero();
    }

    @Test
    void skipModeCommitsCleanRowsAndRecordsTheRest() throws Exception {
        String transferId = startTransfer("/api/items/import-lenient",
                "name,qty\ndelta,4\nbroken,not-a-number\n");
        JsonNode status = awaitTerminal("/api/items/import-lenient/" + transferId);

        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("rows").asLong()).isEqualTo(1);
        assertThat(status.get("errors").get(0).get("row").asLong()).isEqualTo(2);
        assertThat(itemCount("delta")).isEqualTo(1);
    }

    @Test
    void exportStreamsCsvAndMarksRowsInTheExtractionTransaction() throws Exception {
        seedOrder("o-1", false);
        seedOrder("o-2", false);

        String transferId = startTransfer("/api/orders/export", "");
        JsonNode status = awaitTerminal("/api/orders/export/" + transferId);
        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("fileUrl").asText()).endsWith(transferId + "/file");

        // The extract-timed follow-up already marked the rows, before any download happened.
        assertThat(extractedCount()).isEqualTo(2);

        HttpResponse<String> file = get("/api/orders/export/" + transferId + "/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("content-type").orElse("")).contains("text/csv");
        assertThat(file.headers().firstValue("content-disposition").orElse(""))
                .contains("orders.csv");
        assertThat(file.body()).contains("order_no").contains("o-1").contains("o-2");
    }

    @Test
    void downloadTimedFollowUpRunsOnceOnFirstFetch() throws Exception {
        seedOrder("dl-1", true);

        String transferId = startTransfer("/api/orders/export-on-download", "");
        JsonNode status = awaitTerminal("/api/orders/export-on-download/" + transferId);
        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");

        // Nothing is marked until the file is actually fetched.
        assertThat(downloadMarkedCount()).isZero();
        assertThat(get("/api/orders/export-on-download/" + transferId + "/file").statusCode())
                .isEqualTo(200);
        assertThat(downloadMarkedCount()).isEqualTo(1);

        // A second fetch still streams but does not run the follow-up again.
        assertThat(get("/api/orders/export-on-download/" + transferId + "/file").statusCode())
                .isEqualTo(200);
        assertThat(downloadMarkedCount()).isEqualTo(1);

        JsonNode after = MAPPER.readTree(
                get("/api/orders/export-on-download/" + transferId).body());
        assertThat(after.get("downloaded").asBoolean()).isTrue();
    }

    @Test
    void unknownTransferIs404AndRunningExportFileIs409() throws Exception {
        assertThat(get("/api/orders/export/no-such-transfer").statusCode()).isEqualTo(404);
        assertThat(get("/api/orders/export/no-such-transfer/file").statusCode()).isEqualTo(404);
    }

    // --- helpers ---

    private static String startTransfer(String path, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asText();
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            JsonNode status = MAPPER.readTree(get(statusPath).body());
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

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int itemCount(String name) throws Exception {
        return count("select count(*) from items where name = '" + name + "'");
    }

    private static int extractedCount() throws Exception {
        return count("select count(*) from orders where extracted and not download_only");
    }

    private static int downloadMarkedCount() throws Exception {
        return count("select count(*) from orders where extracted and download_only");
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seedOrder(String orderNo, boolean downloadOnly) throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("insert into orders (order_no, extracted, download_only) values ('"
                    + orderNo + "', false, " + downloadOnly + ")");
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-file-transfer-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: file-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"), """
                create table items (name varchar(100) primary key, qty integer not null);
                create table orders (order_no varchar(64) primary key,
                                     extracted boolean not null default false,
                                     download_only boolean not null default false);
                """);

        writeImportRoute(home, "web/api/items/import", "items.import", "rollback");
        writeImportRoute(home, "web/api/items/import-lenient", "items.importLenient", "skip");
        writeExportRoute(home, "web/api/orders/export", "orders.export", "extract",
                "where not download_only");
        writeExportRoute(home, "web/api/orders/export-on-download", "orders.exportOnDownload",
                "download", "where download_only");
        return home;
    }

    private static void writeImportRoute(Path home, String dir, String id, String onError)
            throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: file-import
                import:
                  format: csv
                  columns: [name, qty]
                  onError: %s
                  sql:
                    file: upsert-item.sql
                """.formatted(id, onError));
        Files.writeString(route.resolve("upsert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                ;
                """);
    }

    private static void writeExportRoute(Path home, String dir, String id, String timing,
            String scope) throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: file-export
                export:
                  format: csv
                  filename: orders.csv
                  sql:
                    file: select-orders.sql
                  after:
                    timing: %s
                    sql:
                      file: mark-extracted.sql
                """.formatted(id, timing));
        Files.writeString(route.resolve("select-orders.sql"),
                "select order_no, extracted from orders " + scope + " order by order_no\n;\n");
        Files.writeString(route.resolve("mark-extracted.sql"),
                "update orders set extracted = true where not extracted and "
                        + scope.substring("where ".length()) + "\n;\n");
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
