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

    /** Typed CSV import + export + download: exercises transfers on the vendor schema. */
    static void fileTransferRoundTrip(TesseraqlRuntime runtime, String appName) throws Exception {
        String importId = startTransfer(runtime, "/api/items/import",
                "name,qty\nalpha,1\nbeta,2\n");
        JsonNode imported = awaitTerminal(runtime, "/api/items/import/" + importId);
        assertThat(imported.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(imported.get("rows").asLong()).isEqualTo(2);

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
                sql:
                  file: touch.sql
                  mode: update
                  params:
                    name: body.name
                response:
                  json:
                    status: 200
                    body:
                      affected: sql.affectedRows
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
