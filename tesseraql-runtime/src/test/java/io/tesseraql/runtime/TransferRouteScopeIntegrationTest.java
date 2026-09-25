package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A transfer answers under the route that created it, and nowhere else (docs/edge-hygiene.md
 * E0). The {@code {transferId}} subtree — status, file, cancel — is secured like its parent
 * route, so the parent's policy is what stands between a caller and the bytes. A transfer
 * created under a policy-gated route used to be served by the subtree of ANY file-export or
 * file-import route in the database: the processors resolved the id alone. Here a public export
 * route and a public import route sit beside an ADMIN-gated one, and an anonymous caller holding
 * the admin transfer's id is answered exactly as for an id that does not exist.
 */
@Testcontainers
class TransferRouteScopeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String JWT_SECRET = "scope-secret-for-tests-only-not-a-real-key";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        execute("insert into orders (order_no) values ('o-1'), ('o-2'), ('o-3'), ('o-4')");
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

    /**
     * The headline: the admin export's status, file and card are unreachable through the public
     * routes' subtrees — and reachable, as before, through its own.
     */
    @Test
    void anotherRoutesSubtreeAnswersAnAdminTransferAsUnknown() throws Exception {
        String transferId = startTransfer("/api/orders/export-admin", jwt("ADMIN"));
        JsonNode own = awaitTerminal("/api/orders/export-admin/" + transferId, jwt("ADMIN"));
        assertThat(own.get("status").asString()).isEqualTo("COMPLETED");

        // Without the token the parent route's gate holds: this is what protected the file.
        assertThat(get("/api/orders/export-admin/" + transferId, null).statusCode())
                .isEqualTo(401);

        // Through a public export route's subtree, the same id names nothing — the bytes first,
        // because they are the harm: anonymous, and the admin export's rows came back.
        HttpResponse<String> file = get("/api/orders/export-public/" + transferId + "/file", null);
        assertThat(file.statusCode()).as("anonymous GET of the admin export's file through the"
                + " public route's subtree: %s", file.body()).isEqualTo(404);
        assertThat(file.body()).doesNotContain("order_no").contains("TQL-LD-2822");
        HttpResponse<String> status = get("/api/orders/export-public/" + transferId, null);
        assertThat(status.statusCode()).isEqualTo(404);
        assertThat(status.body()).contains("TQL-LD-2822");
        // The HTML card is the tombstone, not the admin export's card with its Download button.
        HttpResponse<String> card = get("/api/orders/export-public/" + transferId, null,
                "text/html");
        assertThat(card.statusCode()).isEqualTo(200);
        assertThat(card.body()).doesNotContain("/file");

        // Through a public IMPORT route's subtree — the one status endpoint both recipes mount.
        assertThat(get("/api/items/import/" + transferId, null).statusCode()).isEqualTo(404);

        // Its own subtree still serves it, bytes included.
        HttpResponse<String> ownFile = get("/api/orders/export-admin/" + transferId + "/file",
                jwt("ADMIN"));
        assertThat(ownFile.statusCode()).isEqualTo(200);
        assertThat(ownFile.body()).contains("order_no").contains("o-1");
    }

    /**
     * No existence oracle: a foreign transfer's answer is byte-for-byte the unknown-id answer
     * once the id itself is factored out — same status, same code, same message shape.
     */
    @Test
    void aForeignTransferAnswersExactlyAsAnUnknownOne() throws Exception {
        String transferId = startTransfer("/api/orders/export-admin", jwt("ADMIN"));
        awaitTerminal("/api/orders/export-admin/" + transferId, jwt("ADMIN"));

        HttpResponse<String> foreign = get("/api/orders/export-public/" + transferId, null);
        HttpResponse<String> unknown = get("/api/orders/export-public/no-such-transfer", null);
        assertThat(foreign.statusCode()).isEqualTo(unknown.statusCode()).isEqualTo(404);
        assertThat(foreign.body().replace(transferId, "no-such-transfer"))
                .isEqualTo(unknown.body());
    }

    /**
     * A cancel through a foreign route is refused BEFORE it touches the run: the flag the loop
     * polls stays unset. The export is kept running by a per-row {@code pg_sleep}, so the
     * foreign POST lands while the transfer is RUNNING — the only state a cancel can change.
     */
    @Test
    void aCancelThroughAForeignRouteLeavesTheRunUntouched() throws Exception {
        String transferId = startTransfer("/api/orders/export-slow", jwt("ADMIN"));
        JsonNode running = MAPPER.readTree(
                get("/api/orders/export-slow/" + transferId, jwt("ADMIN")).body());
        assertThat(running.get("status").asString()).isIn("RUNNING", "STARTED");

        HttpResponse<String> cancel = post("/api/orders/export-public/" + transferId + "/cancel",
                null);
        assertThat(cancel.statusCode()).isEqualTo(404);
        assertThat(cancelRequestedAt(transferId)).as("cancel flag after a foreign cancel")
                .isNull();

        JsonNode done = awaitTerminal("/api/orders/export-slow/" + transferId, jwt("ADMIN"));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");

        // The control: the same POST through its own subtree, with the token, is answered —
        // and on a finished run it changes nothing either, which is what 200 says here.
        HttpResponse<String> own = post("/api/orders/export-slow/" + transferId + "/cancel",
                jwt("ADMIN"));
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(own.body()).get("cancelRequested").asBoolean()).isFalse();
    }

    /**
     * The export half of the cooperative stop (docs/audit-low-leads.md slice 15, unfiled 8 and
     * XH-26): the mount, the card and the flag promised a stop for imports and exports alike,
     * and only the import loop read the flag — an own-route cancel answered
     * {@code cancelRequested: true} and the export ran to COMPLETED with its file served. The
     * run is paced by a per-row {@code pg_sleep} over six thousand rows, so the row source
     * publishes its counter and reads the flag between fetch batches: the status says how far
     * the run got while RUNNING, the stop lands at a row boundary as STOPPED with the rows it
     * reached, the file answers 409 and the card reads cancelled.
     */
    @Test
    void aCancelThroughItsOwnRouteStopsTheExportAndTheFileAnswers409() throws Exception {
        String transferId = startTransfer("/api/orders/export-long", jwt("ADMIN"));
        JsonNode running = awaitProgress("/api/orders/export-long/" + transferId, jwt("ADMIN"));
        assertThat(running.get("status").asString()).isEqualTo("RUNNING");
        assertThat(running.get("rowCount").asLong()).as("rows reached while RUNNING")
                .isPositive();

        HttpResponse<String> cancel = post("/api/orders/export-long/" + transferId + "/cancel",
                jwt("ADMIN"));
        assertThat(cancel.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(cancel.body()).get("cancelRequested").asBoolean())
                .as("cancel %s after %s", cancel.body(), running).isTrue();

        JsonNode done = awaitTerminal("/api/orders/export-long/" + transferId, jwt("ADMIN"));
        assertThat(done.get("status").asString()).as("terminal status: %s", done)
                .isEqualTo("STOPPED");
        assertThat(done.get("rowCount").asLong()).as("rows reached at the stop")
                .isPositive().isLessThan(6_000);

        HttpResponse<String> file = get("/api/orders/export-long/" + transferId + "/file",
                jwt("ADMIN"));
        assertThat(file.statusCode()).as("a stopped export's file: %s", file.body())
                .isEqualTo(409);
        assertThat(file.body()).contains("TQL-LD-2823");
        HttpResponse<String> card = get("/api/orders/export-long/" + transferId, jwt("ADMIN"),
                "text/html");
        assertThat(card.body()).contains("Cancelled").doesNotContain("/file");
    }

    private static String startTransfer(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = HTTP.send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asString();
    }

    /** The status once the run has published a row count and is still RUNNING. */
    private static JsonNode awaitProgress(String statusPath, String bearer) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            JsonNode status = MAPPER.readTree(get(statusPath, bearer).body());
            if (!"RUNNING".equals(status.get("status").asString())
                    || status.get("rowCount").asLong() > 0) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer published no progress: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static JsonNode awaitTerminal(String statusPath, String bearer) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            JsonNode status = MAPPER.readTree(get(statusPath, bearer).body());
            String value = status.get("status").asString();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        return get(path, bearer, "application/json");
    }

    private static HttpResponse<String> get(String path, String bearer, String accept)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Accept", accept);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static java.sql.Timestamp cancelRequestedAt(String transferId) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = connection.prepareStatement(
                        "select cancel_requested from tql_job_execution"
                                + " where job_execution_id = ?")) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("execution row for " + transferId).isTrue();
                return rs.getTimestamp(1);
            }
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String jwt(String role) throws Exception {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"scope-admin\",\"roles\":[\"" + role + "\"],"
                + "\"aud\":\"https://scope.example.com\",\"exp\":"
                + (System.currentTimeMillis() / 1000 + 3600) + "}");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-transfer-scope-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: scope-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      audience:
                        - https://scope.example.com
                    policies:
                      orders.admin:
                        anyOf:
                          - role: ADMIN
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"), """
                create table items (name varchar(100) primary key, qty integer not null);
                create table orders (order_no varchar(64) primary key);
                """);
        writeExportRoute(home, "web/api/orders/export-admin", "orders.exportAdmin",
                "  auth: bearer\n  policy: orders.admin\n",
                "select order_no from orders order by order_no\n;\n");
        writeExportRoute(home, "web/api/orders/export-public", "orders.exportPublic",
                "  auth: public\n",
                "select order_no from orders order by order_no\n;\n");
        // One row per half second, evaluated per row by the correlated reference: the run stays
        // RUNNING for about two seconds, long enough for a foreign cancel to land on it.
        writeExportRoute(home, "web/api/orders/export-slow", "orders.exportSlow",
                "  auth: bearer\n  policy: orders.admin\n",
                "select order_no, (select 1 from pg_sleep(0.5) where orders.order_no is not null)"
                        + " as pause from orders order by order_no\n;\n");
        // Six thousand rows at two milliseconds each, six fetch batches of a thousand: the run
        // stays RUNNING for about twelve seconds and the row source sees its two-second tick
        // between batches, which a four-row extraction (executed whole before its first row
        // returns) never does. The sleep's ARGUMENT depends on the row: a function scan is
        // materialized and rescanned unless a parameter it reads changed, so a constant
        // pg_sleep behind a row-dependent WHERE runs once for the whole query. No ORDER BY: a
        // sort would run every sleep before the first row.
        writeExportRoute(home, "web/api/orders/export-long", "orders.exportLong",
                "  auth: bearer\n  policy: orders.admin\n",
                "select g as n, (select 1 from pg_sleep(0.002 * sign(g))) as pause"
                        + " from generate_series(1, 6000) g\n;\n");
        writeImportRoute(home);
        return home;
    }

    private static void writeExportRoute(Path home, String dir, String id, String security,
            String sql) throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: file-export
                security:
                %s
                export:
                  format: csv
                  filename: orders.csv
                sources:
                  main:
                    sql:
                      file: select-orders.sql
                """.formatted(id, security.stripTrailing()));
        Files.writeString(route.resolve("select-orders.sql"), sql);
    }

    private static void writeImportRoute(Path home) throws IOException {
        Path route = home.resolve("web/api/items/import");
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: public
                import:
                  format: csv
                  columns: [name, qty]
                  onError: rollback
                steps:
                  - id: row
                    sql:
                      file: upsert-item.sql
                """);
        Files.writeString(route.resolve("upsert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                ;
                """);
    }
}
