package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 26 end-to-end: a {@code poll:}-triggered file-import job watches a local directory; a CSV
 * dropped there is ingested and applied row-by-row through the file-import pipeline, then moved to
 * the done sub-directory.
 */
@Testcontainers
class PollImportLocalIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path inbound;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        inbound = Files.createTempDirectory("tesseraql-poll-inbound");
        Files.writeString(inbound.resolve("orders.csv"), "orderNo,qty\nA-1,3\nA-2,5\n");
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
        if (inbound != null) {
            deleteRecursively(inbound);
        }
    }

    @Test
    void theDroppedCsvIsImportedAndArchived() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        Map<String, Integer> rows = new LinkedHashMap<>();
        // Both conditions, not just the rows: the import now completes inside the consumer's
        // exchange, so the rows become visible a moment *before* Camel archives the polled file.
        // Waiting on the rows alone used to imply the move had happened; it no longer does.
        while (System.currentTimeMillis() < deadline
                && (rows.size() < 2 || !Files.exists(inbound.resolve(".done/orders.csv")))) {
            rows.clear();
            try (Connection connection = connect();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "select order_no, qty from imported_orders order by order_no")) {
                while (rs.next()) {
                    rows.put(rs.getString("order_no"), rs.getInt("qty"));
                }
            }
            if (rows.size() < 2 || !Files.exists(inbound.resolve(".done/orders.csv"))) {
                Thread.sleep(300);
            }
        }

        assertThat(rows).containsEntry("A-1", 3).containsEntry("A-2", 5);
        // The consumer moved the processed file out of the inbound root into the done directory.
        assertThat(Files.exists(inbound.resolve("orders.csv"))).isFalse();
        assertThat(Files.exists(inbound.resolve(".done/orders.csv"))).isTrue();
    }

    @Test
    void aFileThatCannotBeIngestedLandsInTheFailureDirectory() throws Exception {
        // The import rolls back on a bad row (onError: rollback), so nothing is written - and
        // the file must reflect that. It used to land in .done regardless: startImport spools,
        // records the transfer and hands the work to an executor, so the exchange completed
        // before a single row of SQL had run and the consumer archived the file as ingested.
        Files.writeString(inbound.resolve("broken.csv"), "orderNo,qty\nB-1,not-a-number\n");

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline
                && !Files.exists(inbound.resolve(".error/broken.csv"))) {
            Thread.sleep(300);
        }

        assertThat(Files.exists(inbound.resolve(".error/broken.csv")))
                .as("a file whose import failed belongs in moveFailed, not .done")
                .isTrue();
        assertThat(Files.exists(inbound.resolve(".done/broken.csv"))).isFalse();
    }

    /**
     * The poll-source registry (docs/poll-source-status.md): wired at boot as polling, and
     * a successful import stamps the last-poll facts and resets the failure streak.
     */
    @Test
    void thePollSourceRegistryTracksThisNode() throws Exception {
        io.tesseraql.opsui.PollSourceStatus status = runtime.camelContext().getRegistry()
                .lookupByNameAndType("tesseraqlPollSourceStatus",
                        io.tesseraql.opsui.PollSourceStatus.class);
        io.tesseraql.opsui.PollSourceStatus.SourceState wired = status.forJob("orders.intake")
                .orElseThrow();
        assertThat(wired.skipped()).isFalse();
        assertThat(wired.source()).isEqualTo("local");

        Files.writeString(inbound.resolve("registry.csv"), "orderNo,qty\nR-1,1\n");
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline
                && !String.valueOf(status.forJob("orders.intake").orElseThrow().lastResult())
                        .contains("registry.csv")) {
            Thread.sleep(300);
        }

        io.tesseraql.opsui.PollSourceStatus.SourceState state = status.forJob("orders.intake")
                .orElseThrow();
        assertThat(state.lastResult()).contains("registry.csv").contains("imported");
        assertThat(state.lastPollAt()).isNotNull();
        assertThat(state.consecutiveFailures()).isZero();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table imported_orders (order_no varchar(32) primary key, qty int)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-poll-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  connectors:
                    poll:
                      # A local source polls under a declared root, the same deny-by-default rule
                      # a remote source gets from allowedHosts: the consumer moves what it reads,
                      # so an unanchored path relocates a live directory's contents.
                      allowedPaths:
                        - %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), inbound.toAbsolutePath()));

        Path jobDir = target.resolve("batch/intake");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: %s
                    include: "*.csv"
                    delay: 500ms
                import:
                  format: csv
                  onError: rollback
                  columns:
                    - orderNo
                    - { name: qty, type: number }
                pipeline:
                  - id: row
                    sql:
                      file: upsert-order.sql
                """.formatted(inbound.toAbsolutePath()));
        Files.writeString(jobDir.resolve("upsert-order.sql"),
                "insert into imported_orders (order_no, qty)"
                        + " values (/* orderNo */ 'x', /* qty */ 0)\n");
        return target;
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
