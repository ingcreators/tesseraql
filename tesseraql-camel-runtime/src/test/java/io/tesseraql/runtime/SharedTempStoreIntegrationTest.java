package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.operations.spool.JdbcTempStore;
import java.io.IOException;
import java.io.InputStream;
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
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The shared temp store (docs/deployment.md, "Shared export files"): with
 * {@code tesseraql.temp.store: db} a spool written through one store instance is readable
 * through another sharing the database — the export-follows-you property session affinity
 * papered over — and a real query-export route streams through the database store end to end.
 */
@Testcontainers
class SharedTempStoreIntegrationTest {

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
            statement.execute("insert into orders (status) values ('APPROVED')");
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

    private static JdbcTempStore store(Path scratch, long maxBytes) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTempStore store = new JdbcTempStore(dataSource, scratch, maxBytes);
        store.ensureSchema();
        return store;
    }

    /** A spool written on "node A" reads back byte-identical on "node B"; delete crosses too. */
    @Test
    void aSpoolWrittenOnOneNodeIsServedByAnother(@TempDir Path scratchA, @TempDir Path scratchB)
            throws Exception {
        JdbcTempStore nodeA = store(scratchA, JdbcTempStore.DEFAULT_MAX_BYTES);
        JdbcTempStore nodeB = store(scratchB, JdbcTempStore.DEFAULT_MAX_BYTES);

        byte[] payload = "id,status\n1,PENDING\n2,APPROVED\n".getBytes(StandardCharsets.UTF_8);
        SpoolWriter writer = nodeA.createWriter(SpoolKind.CSV);
        writer.write(payload);
        writer.incrementRows(2);
        writer.close();
        SpoolRef ref = writer.toRef();
        assertThat(ref.bytes()).isEqualTo(payload.length);
        assertThat(ref.rows()).isEqualTo(2);

        try (InputStream in = nodeB.openInput(ref)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
        // The staging copies are cleaned up behind both the write and the read.
        try (var files = Files.list(scratchA)) {
            assertThat(files).isEmpty();
        }
        try (var files = Files.list(scratchB)) {
            assertThat(files).isEmpty();
        }

        nodeB.delete(ref);
        assertThatThrownBy(() -> nodeA.openInput(ref)).isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    /** The size cap fails loudly and points at the blob store. */
    @Test
    void aSpoolBeyondMaxBytesFailsLoudly(@TempDir Path scratch) throws Exception {
        JdbcTempStore capped = store(scratch, 8);
        SpoolWriter writer = capped.createWriter(SpoolKind.CSV);
        assertThatThrownBy(() -> writer.write("123456789".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("tesseraql.temp.maxBytes")
                .hasMessageContaining("blob");
    }

    /** A real query-export route streams through the database temp store end to end. */
    @Test
    void aQueryExportStreamsThroughTheDatabaseStore() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/orders/export")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("orders.csv");
        assertThat(response.body()).contains("PENDING").contains("APPROVED");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-temp-store-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: temp-store-it
                  temp:
                    store: db
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path export = target.resolve("web/orders/export");
        Files.createDirectories(export);
        Files.writeString(export.resolve("export.sql"), """
                select
                  o.id,
                  o.status
                from
                  orders o
                order by
                  o.id
                """);
        Files.writeString(export.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.export
                kind: route
                recipe: query-export
                security:
                  auth: public
                sql:
                  file: export.sql
                export:
                  format: csv
                  filename: orders.csv
                """);
        return target;
    }
}
