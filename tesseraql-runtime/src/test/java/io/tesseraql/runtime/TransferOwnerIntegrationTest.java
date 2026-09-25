package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.TesseraqlProperties;
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
import java.util.List;
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
 * A transfer records who started it, and the owner queries list a subject's own and nothing
 * else (docs/job-inbox.md decisions 1 and 3). Two subjects export through the same bearer route,
 * an anonymous caller through a public one, and one subject confirms a reviewed import: each
 * transfer carries the subject of the request that started it — the reviewed commit's frozen
 * request copy included, the place a new component is forgotten — and a transfer nobody started
 * carries none. Who may <em>read</em> a transfer is unchanged: the scope test beside this one
 * keeps pinning that.
 */
@Testcontainers
class TransferOwnerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String JWT_SECRET = "owner-secret-for-tests-only-not-a-real-key";
    private static final String APP = "owner-demo";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static FileTransferService transfers;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        transfers = runtime.context().lookup(TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        execute("insert into orders (order_no) values ('o-1'), ('o-2'), ('o-3')");
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
    void theOwnerQueriesListASubjectsOwnTransfersAndNothingElse() throws Exception {
        String first = startExport("/api/orders/export", jwt("user-a"));
        awaitTerminal("/api/orders/export/" + first, jwt("user-a"));
        String second = startExport("/api/orders/export", jwt("user-a"));
        awaitTerminal("/api/orders/export/" + second, jwt("user-a"));
        String other = startExport("/api/orders/export", jwt("user-b"));
        awaitTerminal("/api/orders/export/" + other, jwt("user-b"));
        String anonymous = startExport("/api/orders/export-public", null);
        awaitTerminal("/api/orders/export-public/" + anonymous, null);

        // Newest first, the subject's own, both of them; nobody else's.
        List<String> mine = transfers.mine(APP, "user-a", null, 50).stream()
                .map(FileTransferService.TransferStatus::transferId).toList();
        assertThat(mine).startsWith(second, first).doesNotContain(other, anonymous);
        assertThat(transfers.mine(APP, "user-b", null, 50))
                .extracting(FileTransferService.TransferStatus::transferId)
                .containsExactly(other);
        // A transfer nobody started belongs to nobody: no subject lists it, and asking for
        // "nobody's" lists nothing rather than everything unowned.
        assertThat(subjectOf(anonymous)).isNull();
        assertThat(transfers.mine(APP, null, null, 50)).isEmpty();
        assertThat(transfers.mine(APP, "", null, 50)).isEmpty();
        // The rows read as the status face reads them, with the moment they started.
        FileTransferService.TransferStatus latest = transfers.mine(APP, "user-a", null, 50)
                .get(0);
        assertThat(latest.status()).isEqualTo("COMPLETED");
        assertThat(latest.direction()).isEqualTo("EXPORT");
        assertThat(latest.createdAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        // The cap hedges the way the tasks page's does: limit + 1 rows say "there are more".
        assertThat(transfers.mine(APP, "user-a", null, 1)).hasSize(1);
        // Under tenancy the owner is scoped with the tenant, the subtree's own rule: these rows
        // were recorded under none, so a tenant sees none of them.
        assertThat(transfers.mine(APP, "user-a", "acme", 50)).isEmpty();
        // The console's summary says who.
        assertThat(transfers.recent(50))
                .filteredOn(summary -> summary.transferId().equals(other))
                .extracting(FileTransferService.TransferSummary::subject)
                .containsExactly("user-b");
        assertThat(transfers.recent(50))
                .filteredOn(summary -> summary.transferId().equals(anonymous))
                .extracting(FileTransferService.TransferSummary::subject)
                .containsExactly((String) null);
    }

    @Test
    void pendingListsWhatStillNeedsItsOwnerAndDropsAFetchedFile() throws Exception {
        String transferId = startExport("/api/orders/export-pending", jwt("user-c"));
        awaitTerminal("/api/orders/export-pending/" + transferId, jwt("user-c"));

        // Done and not yet fetched: the file is waiting for its owner.
        assertThat(transfers.pending(APP, "orders.exportPending", "user-c", null, 5))
                .extracting(FileTransferService.TransferStatus::transferId)
                .containsExactly(transferId);
        // Another route's pending list, or another subject's, does not hold it.
        assertThat(transfers.pending(APP, "orders.export", "user-c", null, 5)).isEmpty();
        assertThat(transfers.pending(APP, "orders.exportPending", "user-a", null, 5)).isEmpty();

        HttpResponse<String> file = get("/api/orders/export-pending/" + transferId + "/file",
                jwt("user-c"));
        assertThat(file.statusCode()).isEqualTo(200);
        // Fetched: dealt with, and gone from what still needs the owner.
        assertThat(transfers.pending(APP, "orders.exportPending", "user-c", null, 5)).isEmpty();
        // The page that lists everything still has it.
        assertThat(transfers.mine(APP, "user-c", null, 50))
                .extracting(FileTransferService.TransferStatus::transferId)
                .contains(transferId);
    }

    @Test
    void aReviewedCommitRecordsTheConfirmerAsTheOwner() throws Exception {
        HttpResponse<String> review = send(HttpRequest.newBuilder(uri("/api/items/import"))
                .header("Authorization", "Bearer " + jwt("user-a"))
                .header("Accept", "application/json")
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("name,qty\nalpha,1\nbeta,2\n"))
                .build());
        assertThat(review.statusCode()).as(review.body()).isEqualTo(200);
        String token = MAPPER.readTree(review.body()).get("token").asString();

        HttpResponse<String> commit = send(HttpRequest.newBuilder(
                uri("/api/items/import/" + token + "/commit"))
                .header("Authorization", "Bearer " + jwt("user-a"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertThat(commit.statusCode()).as(commit.body()).isEqualTo(202);
        String transferId = MAPPER.readTree(commit.body()).get("transferId").asString();
        JsonNode done = awaitTerminal("/api/items/import/" + transferId, jwt("user-a"));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");

        // The frozen request copy the commit launches carries the confirmer the commit checked
        // — the copy that once dropped the topics, the tenant and the pool.
        assertThat(subjectOf(transferId)).isEqualTo("user-a");
        assertThat(transfers.mine(APP, "user-a", null, 50))
                .filteredOn(status -> status.transferId().equals(transferId))
                .extracting(FileTransferService.TransferStatus::direction)
                .containsExactly("IMPORT");
    }

    private static String subjectOf(String transferId) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = connection.prepareStatement(
                        "select subject from tql_file_transfer where transfer_id = ?")) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("transfer row for " + transferId).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String startExport(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = send(request.build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asString();
    }

    private static JsonNode awaitTerminal(String statusPath, String bearer) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            JsonNode status = MAPPER.readTree(get(statusPath, bearer).body());
            String value = status.get("status").asString();
            if (!"RUNNING".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + runtime.port() + path);
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** A token for {@code subject}, holding the one role the routes' policy asks for. */
    private static String jwt(String subject) throws Exception {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\",\"roles\":[\"USER\"],"
                + "\"aud\":\"https://owner.example.com\",\"exp\":"
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
        Path home = Files.createTempDirectory("tesseraql-transfer-owner-it");
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
                  security:
                    jwt:
                      secret: %s
                      audience:
                        - https://owner.example.com
                    policies:
                      orders.user:
                        anyOf:
                          - role: USER
                """.formatted(APP, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"), """
                create table items (name varchar(100) primary key, qty integer not null);
                create table orders (order_no varchar(64) primary key);
                """);
        writeExportRoute(home, "web/api/orders/export", "orders.export",
                "  auth: bearer\n  policy: orders.user\n");
        writeExportRoute(home, "web/api/orders/export-pending", "orders.exportPending",
                "  auth: bearer\n  policy: orders.user\n");
        writeExportRoute(home, "web/api/orders/export-public", "orders.exportPublic",
                "  auth: public\n");
        writeImportRoute(home);
        return home;
    }

    private static void writeExportRoute(Path home, String dir, String id, String security)
            throws IOException {
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
        Files.writeString(route.resolve("select-orders.sql"),
                "select order_no from orders order by order_no\n;\n");
    }

    /** A reviewed import: the upload parks a batch, the confirm launches the transfer. */
    private static void writeImportRoute(Path home) throws IOException {
        Path route = home.resolve("web/api/items/import");
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: orders.user
                import:
                  format: csv
                  columns: [name, qty]
                  onError: rollback
                  review: required
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
