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
import java.sql.ResultSet;
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
 * A {@code file-import} route without {@code format:} reads csv, as docs/file-transfers.md
 * says a route's absent format is and as an export's already was: the processor used to be
 * handed the literal null, and the first upload answered 500 {@code TQL-LD-2801} for the format
 * {@code 'null'} (docs/codec-discovery.md decision 2, found while every arm was made to look its
 * codec up at boot).
 */
@Testcontainers
class ImportFormatDefaultIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        port = runtime.port();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void anImportWithoutAFormatReadsCsv() throws Exception {
        HttpResponse<String> started = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/items/import"))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("name,qty\nalpha,1\nbeta,2\n",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String id = MAPPER.readTree(started.body()).get("transferId").asText();
        JsonNode status = awaitTerminal("/api/items/import/" + id);
        assertThat(status.get("status").asText()).as(status.toString()).isEqualTo("COMPLETED");
        assertThat(itemCount()).isEqualTo(2);
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + statusPath)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode status = MAPPER.readTree(response.body());
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

    private static int itemCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from items")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("import-format-default-app");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: import-format-default-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__items.sql"),
                "create table items (name varchar(100) primary key, qty integer not null);\n");
        Path route = Files.createDirectories(home.resolve("web/api/items/import"));
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                import:
                  columns:
                    - name
                    - { name: qty, type: number }
                steps:
                  - id: row
                    sql:
                      file: insert-item.sql
                """);
        Files.writeString(route.resolve("insert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                """);
        return home;
    }
}
