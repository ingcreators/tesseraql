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
import java.time.Duration;
import java.time.Instant;

/**
 * Shared live checks for the dialect portability tests (design ch. 42): the outbox round trip
 * (command route writes the event transactionally, the dispatcher claims it through the
 * dialect's claim variant and delivers) and the file transfer round trip (typed CSV import,
 * status polling, export, download) - the paths whose SQL diverges per vendor.
 */
final class DialectRuntimeChecks {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private DialectRuntimeChecks() {
    }

    /**
     * The identifier contract on this vendor (docs/unicode-identifiers.md): unquoted
     * Japanese table and column names create, insert, and select — and the row-map path
     * ({@code ResultRows.label}) hands the labels back verbatim, which on Oracle also pins
     * the caseless no-op of the all-uppercase fold heuristic.
     */
    static void japaneseIdentifiersRoundTrip(javax.sql.DataSource dataSource, String dialect,
            String varcharType) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("create table 受注 (受注番号 " + varcharType.formatted(20)
                    + " primary key, 顧客名 " + varcharType.formatted(100) + " not null)");
            // Values go through bind parameters, as the framework sends them — a bare
            // Japanese literal would need N'…' on SQL Server (a value concern, not an
            // identifier one; the driver sends parameters as Unicode).
            try (var insert = connection.prepareStatement(
                    "insert into 受注 (受注番号, 顧客名) values (?, ?)")) {
                insert.setString(1, "J-1001");
                insert.setString(2, "山田商事");
                insert.executeUpdate();
            }
            try (var results = statement.executeQuery(
                    "select 受注番号, 顧客名 from 受注 where 受注番号 = 'J-1001'")) {
                assertThat(results.next()).isTrue();
                var metaData = results.getMetaData();
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    row.put(io.tesseraql.core.dialect.ResultRows.label(dialect,
                            metaData.getColumnLabel(column)), results.getObject(column));
                }
                assertThat(row).containsEntry("受注番号", "J-1001")
                        .containsEntry("顧客名", "山田商事");
            }
        }
    }

    /** Command + outbox + dispatch: exercises the vendor's claim variant end to end. */
    static void outboxRoundTrip(TesseraqlRuntime runtime) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/users/touch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"sato\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("affected").asInt()).isEqualTo(1);

        assertThat(runtime.outboxStore().listPending(10))
                .anyMatch(event -> "USER_TOUCHED".equals(event.eventType()));
        assertThat(runtime.dispatchOutboxOnce()).isGreaterThanOrEqualTo(1);
        assertThat(runtime.outboxStore().listPending(10)).isEmpty();
    }

    /**
     * Messaging event channel (roadmap Phase 27): exercises the vendor's {@code SKIP LOCKED} claim
     * variant for the {@code db-poll} transport — publish, claim (the per-dialect query), then
     * consume with idempotency-key dedup — directly against the dialect's datasource.
     */
    static void eventChannelRoundTrip(javax.sql.DataSource dataSource) {
        io.tesseraql.operations.messaging.JdbcEventChannelStore store = new io.tesseraql.operations.messaging.JdbcEventChannelStore(
                dataSource);
        store.ensureSchema();
        String id = store.publish("events", "orders.created", "K-1", "{\"orderId\":\"K-1\"}",
                "dialect-check");

        // The claim renders the dialect's variant (Oracle ROWNUM / SQL Server TOP+READPAST); a
        // claimed-but-unconsumed row is not re-claimed within the abandoned window.
        assertThat(store.claim("events", "orders.created", 10))
                .extracting(io.tesseraql.core.messaging.EventMessage::id).contains(id);
        assertThat(store.claim("events", "orders.created", 10)).isEmpty();

        assertThat(store.consumed("events", "orders.created", "K-1")).isFalse();
        store.markConsumed(id, "events", "orders.created", "K-1");
        assertThat(store.consumed("events", "orders.created", "K-1")).isTrue();
        // A consumed message is never claimed again.
        assertThat(store.claim("events", "orders.created", 10)).isEmpty();
    }

    /**
     * The code-catalog version table (docs/lookups.md, decision 14) on this vendor's DDL.
     *
     * <p>Its own check because the table is created only by an app that declares
     * {@code catalogs/}, so no other dialect test would ever apply it — which is exactly how a
     * vendor variant stays broken until someone runs that app on that database.
     */
    static void catalogVersionTableApplies(javax.sql.DataSource dataSource) throws Exception {
        io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                io.tesseraql.operations.catalog.JdbcCatalogStore.class,
                "/tesseraql/db/migration/catalog/V1__catalog_version.sql");
        try (java.sql.Connection connection = dataSource.getConnection()) {
            try (java.sql.PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_catalog_version (table_name, version, updated_at)"
                            + " values (?, 1, ?)")) {
                insert.setString(1, "区分マスタ");
                // A current instant, not the epoch: MySQL's TIMESTAMP range starts at
                // 1970-01-01 00:00:01 UTC and rejects the boundary value outright.
                insert.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
                insert.executeUpdate();
            }
            try (java.sql.PreparedStatement update = connection.prepareStatement(
                    "update tql_catalog_version set version = version + 1 where table_name = ?")) {
                update.setString(1, "区分マスタ");
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (java.sql.PreparedStatement select = connection.prepareStatement(
                    "select version from tql_catalog_version where table_name = ?")) {
                select.setString(1, "区分マスタ");
                try (java.sql.ResultSet rows = select.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(2L);
                }
            }
        }
        // Applying it twice is what a second boot does.
        io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                io.tesseraql.operations.catalog.JdbcCatalogStore.class,
                "/tesseraql/db/migration/catalog/V1__catalog_version.sql");
    }

    /** Typed CSV import + export + download: exercises transfers on the vendor schema. */
    static void fileTransferRoundTrip(TesseraqlRuntime runtime, String appName) throws Exception {
        String importId = startTransfer(runtime, "/api/items/import",
                "name,qty\nalpha,1\nbeta,2\n");
        JsonNode imported = awaitTerminal(runtime, "/api/items/import/" + importId);
        assertThat(imported.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(imported.get("rowCount").asLong()).isEqualTo(2);

        String exportId = startTransfer(runtime, "/api/items/export", "");
        assertThat(awaitTerminal(runtime, "/api/items/export/" + exportId)
                .get("status").asText()).isEqualTo("COMPLETED");
        HttpResponse<String> file = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/api/items/export/" + exportId + "/file"))
                .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(file.statusCode()).isEqualTo(200);
        // Lowercase headers prove the label normalization on uppercase-folding dialects.
        assertThat(file.body()).contains("name,qty").contains("alpha").contains("beta");

        assertThat(runtime.fileTransfers().recent(10)).isNotEmpty()
                .allSatisfy(transfer -> assertThat(transfer.appName()).isEqualTo(appName));
    }

    /** Writes the command/import/export routes shared by the dialect test apps. */
    static void writeTransferRoutes(Path home) throws IOException {
        Path touch = home.resolve("web/api/users/touch");
        Files.createDirectories(touch);
        Files.writeString(touch.resolve("post.yml"), """
                version: tesseraql/v1
                id: users.touch
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                    required: true
                outbox:
                  eventType: USER_TOUCHED
                  aggregateType: User
                  aggregateId: body.name
                  payload:
                    name: body.name
                steps:
                  - id: main
                    sql:
                      file: touch.sql
                      mode: update
                      params:
                        name: body.name
                response:
                  json:
                    status: 200
                    body:
                      affected: steps.main.affectedRows
                """);
        Files.writeString(touch.resolve("touch.sql"),
                "update users set status = status where name = /* name */ 'x'\n");

        Path importRoute = home.resolve("web/api/items/import");
        Files.createDirectories(importRoute);
        Files.writeString(importRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                import:
                  format: csv
                  columns:
                    - name
                    - { name: qty, type: number }
                steps:
                  - id: row
                    sql:
                      file: insert-item.sql
                """);
        Files.writeString(importRoute.resolve("insert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', /* qty */ 1 )
                ;
                """);

        Path exportRoute = home.resolve("web/api/items/export");
        Files.createDirectories(exportRoute);
        Files.writeString(exportRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: file-export
                export:
                  format: csv
                  filename: items.csv
                sources:
                  main:
                    sql:
                      file: select-items.sql
                """);
        Files.writeString(exportRoute.resolve("select-items.sql"),
                "select name, qty from items order by name\n;\n");
    }

    private static String startTransfer(TesseraqlRuntime runtime, String path, String body)
            throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asText();
    }

    private static JsonNode awaitTerminal(TesseraqlRuntime runtime, String statusPath)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + statusPath)).build(),
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
}
