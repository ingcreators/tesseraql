package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.spool.JdbcTempStore;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
 *
 * <p>The asynchronous faces of the same store: a completed {@code file-export} downloads, an
 * export-then-push job delivers, and the retention sweep reclaims the rows. Each one addresses
 * the spool by the id the store minted (docs/export-hygiene.md), never by the transfer id — the
 * synchronous route above never touches that path, which is why it alone was green before.
 */
@Testcontainers
class SharedTempStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String DB_SCHEME = "tql-temp-db:";

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
        runtime = TesseraqlRuntime.start(appHome, 0);
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

    /**
     * A csv route with a named source whose value is 21,846 Japanese characters exports — and
     * leaves no orphan spool (docs/export-hygiene.md P2). The named source is drained whatever
     * the codec, and its drain used to fail past {@code writeUTF}'s 65,535-byte ceiling with
     * {@code TQL-LD-2855}, leaving a header-only spool behind on every breach.
     */
    @Test
    void aNamedSourcePastTheUtf8CeilingExportsAndLeavesNoOrphan() throws Exception {
        long before = spoolRowsTotal();
        HttpResponse<String> response = get("/orders/export-noted");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("PENDING").contains("APPROVED");
        assertThat(spoolRowsTotal()).as("no orphan spool row").isEqualTo(before);
    }

    private static long spoolRowsTotal() throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from tql_temp_spool");
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
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

    /**
     * A completed file-export under the database store answers its download. The store keys the
     * bytes by the spool id it minted; a download that looked the transfer id up instead found
     * nothing and answered 500 for every export the sync route did not serve.
     */
    @Test
    void anAsyncExportUnderTheDatabaseStoreDownloads() throws Exception {
        String transferId = startExport("/api/orders/export-async");
        JsonNode status = awaitTerminal("/api/orders/export-async/" + transferId);
        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(spoolUriOf(transferId)).startsWith(DB_SCHEME);

        HttpResponse<String> file = get("/api/orders/export-async/" + transferId + "/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("content-type").orElse("")).contains("text/csv");
        assertThat(file.body()).contains("PENDING").contains("APPROVED");
        JsonNode after = MAPPER.readTree(get("/api/orders/export-async/" + transferId).body());
        assertThat(after.get("downloaded").asBoolean()).isTrue();
    }

    /**
     * A zero-row export on the asynchronous and job arms carries the query's column names (P5,
     * rule 2): the file-export download and the job step's spool both read {@code id,status}
     * where they used to be 0 bytes.
     */
    @Test
    void aZeroRowExportCarriesItsHeaderOnTheAsyncAndJobArms() throws Exception {
        String transferId = startExport("/api/orders/export-none");
        assertThat(awaitTerminal("/api/orders/export-none/" + transferId).get("status").asText())
                .isEqualTo("COMPLETED");
        HttpResponse<String> file = get("/api/orders/export-none/" + transferId + "/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo("id,status\r\n");

        JobExecution execution = runtime.runJob("orders.noneJob", Map.of());
        assertThat(execution.status().name()).as(execution.exitMessage()).isEqualTo("COMPLETED");
        Path delivered = appHome.resolve("outbox/partner/none.csv");
        assertThat(delivered).exists();
        assertThat(Files.readString(delivered)).isEqualTo("id,status\r\n");
    }

    /** An export step followed by a push step delivers the file — the push reads through download. */
    @Test
    void anExportAndPushJobUnderTheDatabaseStoreDelivers() throws Exception {
        JobExecution execution = runtime.runJob("orders.deliver", Map.of());
        assertThat(execution.status().name()).as(execution.exitMessage()).isEqualTo("COMPLETED");
        Path delivered = appHome.resolve("outbox/partner/orders.csv");
        assertThat(delivered).exists();
        assertThat(Files.readString(delivered)).contains("PENDING").contains("APPROVED");
    }

    /**
     * The retention sweep frees the spool rows of the transfers it expires. It used to null the
     * pointer and leave the bytes: the delete named the transfer id, which no row carries.
     */
    @Test
    void theSweepReclaimsTheSpoolRowsUnderTheDatabaseStore() throws Exception {
        String transferId = startExport("/api/orders/export-async");
        assertThat(awaitTerminal("/api/orders/export-async/" + transferId).get("status").asText())
                .isEqualTo("COMPLETED");
        String spoolUri = spoolUriOf(transferId);
        assertThat(spoolUri).startsWith(DB_SCHEME);
        String spoolId = spoolUri.substring(DB_SCHEME.length());
        assertThat(spoolRows(spoolId)).isEqualTo(1);

        FileTransferService transfers = runtime.context().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN, FileTransferService.class);
        int expired = transfers.expireTransfersOlderThan(Instant.now().plusSeconds(60));
        assertThat(expired).isGreaterThanOrEqualTo(1);

        assertThat(spoolRows(spoolId)).as("the bytes are reclaimed, not only the pointer")
                .isZero();
        assertThat(spoolUriOf(transferId)).isNull();
    }

    /**
     * A download that cannot open its bytes is not recorded as delivered: the first-download
     * claim (and the after-download SQL it gates) follows a successful open, not the request.
     */
    @Test
    void aDownloadThatFailsIsNotRecordedAsDelivered() throws Exception {
        String transferId = startExport("/api/orders/export-async");
        assertThat(awaitTerminal("/api/orders/export-async/" + transferId).get("status").asText())
                .isEqualTo("COMPLETED");
        String spoolId = spoolUriOf(transferId).substring(DB_SCHEME.length());
        // The bytes vanish from under the transfer — an operator's delete, a foreign sweep.
        update("delete from tql_temp_spool where spool_id = ?", spoolId);
        assertThat(spoolRows(spoolId)).isZero();

        HttpResponse<String> file = get("/api/orders/export-async/" + transferId + "/file");
        assertThat(file.statusCode()).isNotEqualTo(200);
        JsonNode after = MAPPER.readTree(get("/api/orders/export-async/" + transferId).body());
        assertThat(after.get("downloaded").asBoolean())
                .as("a failed download is not a download").isFalse();
    }

    private static String startExport(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
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

    private static String spoolUriOf(String transferId) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "select spool_uri from tql_file_transfer where transfer_id = ?")) {
            statement.setString(1, transferId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("transfer row " + transferId).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static long spoolRows(String spoolId) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from tql_temp_spool where spool_id = ?")) {
            statement.setString(1, spoolId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static void update(String sql, String parameter) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            statement.executeUpdate();
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
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
                  connectors:
                    push:
                      allowedPaths: [outbox]
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
                sources:
                  main:
                    sql:
                      file: export.sql
                export:
                  format: csv
                  filename: orders.csv
                """);
        Path noted = target.resolve("web/orders/export-noted");
        Files.createDirectories(noted);
        Files.writeString(noted.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.exportNoted
                kind: route
                recipe: query-export
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: export.sql
                  note:
                    sql:
                      file: note.sql
                export:
                  format: csv
                  filename: orders.csv
                """);
        Files.copy(export.resolve("export.sql"), noted.resolve("export.sql"));
        Files.writeString(noted.resolve("note.sql"), "select repeat('い', 21846) as note\n");
        Path async = target.resolve("web/api/orders/export-async");
        Files.createDirectories(async);
        Files.writeString(async.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.exportAsync
                kind: route
                recipe: file-export
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: export.sql
                export:
                  format: csv
                  filename: orders.csv
                """);
        Files.copy(export.resolve("export.sql"), async.resolve("export.sql"));
        Path none = target.resolve("web/api/orders/export-none");
        Files.createDirectories(none);
        Files.writeString(none.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.exportNone
                kind: route
                recipe: file-export
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: none.sql
                export:
                  format: csv
                  filename: none.csv
                """);
        Files.writeString(none.resolve("none.sql"),
                "select o.id, o.status from orders o where o.id < 0 order by o.id\n");
        Path noneJob = target.resolve("batch/none");
        Files.createDirectories(noneJob);
        Files.writeString(noneJob.resolve("job.yml"), """
                version: tesseraql/v1
                id: orders.noneJob
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    sql:
                      file: none.sql
                      mode: query
                    export:
                      format: csv
                      filename: none.csv
                  - id: drop
                    push:
                      transport: local
                      path: outbox/partner
                      file: steps.extract.transferId
                      as: none.csv
                """);
        Files.copy(none.resolve("none.sql"), noneJob.resolve("none.sql"));
        Path deliver = target.resolve("batch/deliver");
        Files.createDirectories(deliver);
        Files.writeString(deliver.resolve("job.yml"), """
                version: tesseraql/v1
                id: orders.deliver
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    sql:
                      file: export.sql
                      mode: query
                    export:
                      format: csv
                      filename: orders.csv
                  - id: drop
                    push:
                      transport: local
                      path: outbox/partner
                      file: steps.extract.transferId
                      as: orders.csv
                """);
        Files.copy(export.resolve("export.sql"), deliver.resolve("export.sql"));
        return target;
    }
}
