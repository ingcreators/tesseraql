package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The row ceiling follows the buffering, not the path (docs/export-pipeline.md, decision 7).
 * A {@code format: pdf} export holds every row, then the whole document as one string, then as one
 * byte array, and until this there was nothing at all between it and the heap — {@code maxRows}
 * lived only on the materializing-query path. A CSV export of the same query writes each row
 * through as it arrives, so it stays uncapped: a ceiling there would exist only to be raised.
 */
@Testcontainers
class ExportRowCapIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        // Port 0: the runtime binds an ephemeral port and reports it, so no
        // pick-then-bind race with parallel suites (the freePort() TOCTOU flake).
        runtime = TesseraqlRuntime.start(appHome, 0);
        port = runtime.port();
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
    void aBufferingExportPastItsCeilingFailsAndNamesTheRemedy() throws Exception {
        HttpResponse<String> response = get("/api/items/print");

        assertThat(response.statusCode()).isNotEqualTo(200);
        // The wire carries the code; the message stays server-side, as every other error's does.
        // ExportRowCapTest covers what it says.
        assertThat(response.body()).contains("TQL-LD-2850");
    }

    @Test
    void aSplitExportBundlesOneDocumentPerGroup() throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/items/bundle"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        // One file leaves the export whatever the document format inside it, so the spool, the
        // transfer record and every delivery destination keep working untouched.
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/zip");
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains(".zip");

        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(response.body()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                // Each entry is a document in its own right, not a fragment of one.
                assertThat(zip.readAllBytes()).isNotEmpty();
            }
        }
        assertThat(names).containsExactlyInAnyOrder("items-east.csv", "items-west.csv");
    }

    @Test
    void theSameQueryThroughAStreamingCodecIsNotCapped() throws Exception {
        HttpResponse<String> response = get("/api/items/dump");

        assertThat(response.statusCode()).isEqualTo(200);
        // All three rows, though the buffering export of the same query stops at two.
        assertThat(response.body()).contains("alpha", "beta", "gamma");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("export-cap-app");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: cap-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__items.sql"), """
                create table items (name varchar(100) primary key, region varchar(20));
                insert into items (name, region) values ('alpha', 'east');
                insert into items (name, region) values ('beta', 'west');
                insert into items (name, region) values ('gamma', 'east');
                """);

        Path print = home.resolve("web/api/items/print");
        Files.createDirectories(print);
        Files.writeString(print.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.print
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: items.sql
                export:
                  format: pdf
                  filename: items.pdf
                  maxRows: 2
                  columns:
                    - { name: name, label: Name }
                """);
        Files.writeString(print.resolve("items.sql"), "select name from items order by name\n;\n");

        Path dump = home.resolve("web/api/items/dump");
        Files.createDirectories(dump);
        Files.writeString(dump.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: items.sql
                export:
                  format: csv
                  filename: items.csv
                  columns:
                    - { name: name, label: Name }
                """);
        Files.writeString(dump.resolve("items.sql"), "select name from items order by name\n;\n");

        Path bundle = home.resolve("web/api/items/bundle");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.bundle
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: regions.sql
                export:
                  format: csv
                  filename: items-{key}.csv
                  splitBy: region
                  columns:
                    - { name: name, label: Name }
                """);
        Files.writeString(bundle.resolve("regions.sql"),
                "select region, name from items order by region, name\n;\n");
        return home;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
