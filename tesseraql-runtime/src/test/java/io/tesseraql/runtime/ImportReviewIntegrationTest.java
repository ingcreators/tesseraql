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
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The reviewed upload, end to end over HTTP (docs/csv-import.md slice 1): an
 * {@code import.review: required} route parses and validates without writing, answers a report
 * and a single-shot token, and a confirm spends that token to run an ordinary import.
 *
 * <p>The routes are bearer-authenticated on purpose. A parked batch is one subject's to commit,
 * and a test whose principal is always absent would assert that scoping against nothing.
 */
@Testcontainers
class ImportReviewIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String JWT_SECRET = "review-secret-for-tests-only-not-a-real-key";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
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

    @BeforeEach
    void clearItems() throws Exception {
        execute("delete from items");
    }

    @Test
    void aCleanUploadParksTheBatchAndWritesNothingUntilTheCommit() throws Exception {
        HttpResponse<String> upload = upload("/api/items/import", "name,qty\nalpha,1\nbeta,2\n",
                "importer");
        assertThat(upload.statusCode()).isEqualTo(200);
        JsonNode report = MAPPER.readTree(upload.body());

        // 200 and no Location: nothing was accepted for processing, which is the whole point.
        assertThat(upload.headers().firstValue("Location")).isEmpty();
        assertThat(report.get("rowCount").asLong()).isEqualTo(2);
        assertThat(report.get("ready").asLong()).isEqualTo(2);
        assertThat(report.get("rejected").asLong()).isZero();
        assertThat(report.get("token").asText()).isNotBlank();
        assertThat(itemCount()).isZero();

        JsonNode status = commitAndAwait("/api/items/import", report.get("token").asText(),
                "importer");
        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("rowCount").asLong()).isEqualTo(2);
        assertThat(itemCount()).isEqualTo(2);
    }

    @Test
    void skipOffersThePartialImportAndNamesTheColumnAndValueItRefused() throws Exception {
        HttpResponse<String> upload = upload("/api/items/import-lenient",
                "name,qty\ndelta,4\nbroken,not-a-number\n", "importer");
        assertThat(upload.statusCode()).isEqualTo(200);
        JsonNode report = MAPPER.readTree(upload.body());

        assertThat(report.get("ready").asLong()).isEqualTo(1);
        assertThat(report.get("rejected").asLong()).isEqualTo(1);
        JsonNode error = report.get("errors").get(0);
        assertThat(error.get("row").asLong()).isEqualTo(2);
        // The report is a Row / Field / Message table, so the field and the rejected text ride
        // as data rather than only inside an English sentence.
        assertThat(error.get("field").asText()).isEqualTo("qty");
        assertThat(error.get("value").asText()).isEqualTo("not-a-number");

        JsonNode status = commitAndAwait("/api/items/import-lenient",
                report.get("token").asText(), "importer");
        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(itemCount()).isEqualTo(1);
    }

    @Test
    void rollbackOffersNothingToConfirmWhenAnyRowIsRejected() throws Exception {
        HttpResponse<String> upload = upload("/api/items/import",
                "name,qty\ngamma,3\nbroken,not-a-number\n", "importer");

        // All or nothing is the declaration, so three good rows and one bad leave no committable
        // set — and the status code says so rather than the report contradicting a confirm form.
        assertThat(upload.statusCode()).isEqualTo(422);
        JsonNode report = MAPPER.readTree(upload.body());
        assertThat(report.has("token")).isFalse();
        assertThat(report.get("ready").asLong()).isEqualTo(1);
        assertThat(report.get("rejected").asLong()).isEqualTo(1);
        assertThat(itemCount()).isZero();
    }

    @Test
    void anUnreadableFileIsReportedAsAFileErrorRatherThanAsNothing() throws Exception {
        // A header that does not map used to end as a failed transfer with an empty error list.
        HttpResponse<String> upload = upload("/api/items/import",
                "name,quantity\nalpha,1\n", "importer");

        assertThat(upload.statusCode()).isEqualTo(422);
        JsonNode report = MAPPER.readTree(upload.body());
        assertThat(report.has("token")).isFalse();
        assertThat(report.get("fileError").asText()).contains("qty");
    }

    @Test
    void aRowTheDatabaseRefusesIsNotAParseDisagreement() throws Exception {
        // Blank parses to null, which the column accepts and `qty integer not null` does not:
        // a row that is fine on the file's terms and impossible on the database's. The review
        // therefore finds nothing and offers the whole file, and the commit's own rejection
        // must be read as `onError: skip` doing its job — not as the file having changed since
        // it was reviewed, which would roll back an import that behaved exactly as declared.
        HttpResponse<String> upload = upload("/api/items/import-lenient",
                "name,qty\nalpha,1\nbeta,\n", "importer");
        assertThat(upload.statusCode()).isEqualTo(200);
        JsonNode report = MAPPER.readTree(upload.body());
        assertThat(report.get("rejected").asLong()).isZero();
        assertThat(report.get("ready").asLong()).isEqualTo(2);

        JsonNode status = commitAndAwait("/api/items/import-lenient",
                report.get("token").asText(), "importer");

        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("errors").get(0).get("row").asLong()).isEqualTo(2);
        // The write pass has no column to blame, and says so with an absence.
        assertThat(status.get("errors").get(0).has("field")).isFalse();
        assertThat(itemCount()).isEqualTo(1);
    }

    @Test
    void theTokenIsSingleShot() throws Exception {
        String token = tokenFor("/api/items/import", "name,qty\nalpha,1\n", "importer");

        // Await the import each time a commit is accepted: it runs on a background thread, and
        // an import still writing after its own test ends would land in the next test's table.
        commitAndAwait("/api/items/import", token, "importer");
        HttpResponse<String> replay = commit("/api/items/import", token, "importer");
        assertThat(replay.statusCode()).isEqualTo(409);
    }

    @Test
    void aReUploadSupersedesTheBatchBeforeIt() throws Exception {
        String first = tokenFor("/api/items/import", "name,qty\nalpha,1\n", "importer");
        String second = tokenFor("/api/items/import", "name,qty\nbeta,2\n", "importer");
        assertThat(second).isNotEqualTo(first);

        // Two live tokens would let the author review one file and commit the other.
        HttpResponse<String> superseded = commit("/api/items/import", first, "importer");
        assertThat(superseded.statusCode()).isEqualTo(409);
        // And the refusal says what actually happened. "Already committed" would be the one
        // wrong sentence here: nothing was committed, a newer upload replaced it.
        assertThat(superseded.body()).contains("newer upload");
        commitAndAwait("/api/items/import", second, "importer");
        assertThat(itemCount()).isEqualTo(1);
    }

    @Test
    void anotherSubjectCannotSpendTheBatch() throws Exception {
        String token = tokenFor("/api/items/import", "name,qty\nalpha,1\n", "importer");

        assertThat(commit("/api/items/import", token, "someone-else").statusCode())
                .isEqualTo(409);
        commitAndAwait("/api/items/import", token, "importer");
    }

    @Test
    void aTokenThatDisagreesWithTheAddressItPostedToIsRefused() throws Exception {
        String token = tokenFor("/api/items/import", "name,qty\nalpha,1\n", "importer");

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/api/items/import/" + token + "/commit?token=not-the-same"))
                .header("Authorization", "Bearer " + jwt("importer"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(409);
        // The mismatch refused before the claim, so the honest token still works.
        commitAndAwait("/api/items/import", token, "importer");
    }

    @Test
    void anExpiredBatchIsToldItExpiredRatherThanThatItNeverExisted() throws Exception {
        String token = tokenFor("/api/items/import", "name,qty\nalpha,1\n", "importer");

        // Drive the sweep directly: it is scheduled at a quarter of the review window, which no
        // test should wait out, and the cutoff is what decides expiry either way.
        io.tesseraql.core.files.FileTransferService transfers = runtime.context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.FILE_TRANSFER_BEAN,
                io.tesseraql.core.files.FileTransferService.class);
        assertThat(transfers.expireReviewBatches(Instant.now().plus(Duration.ofHours(2))))
                .isEqualTo(1);

        HttpResponse<String> response = commit("/api/items/import", token, "importer");
        assertThat(response.statusCode()).isEqualTo(409);
        // The row outlived its bytes, which is the whole reason the sweep leaves it behind.
        assertThat(response.body()).contains("review window");
        assertThat(itemCount()).isZero();
    }

    @Test
    void anImportWithoutReviewKeepsItsOneShotShape() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/items/import-direct"))
                .header("Content-Type", "text/csv")
                .header("Authorization", "Bearer " + jwt("importer"))
                .POST(HttpRequest.BodyPublishers.ofString("name,qty\nalpha,1\n",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        // Unchanged: 202, a transfer id, and a Location pointing at the status resource.
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("Location")).isPresent();
        String transferId = MAPPER.readTree(response.body()).get("transferId").asText();
        assertThat(awaitTerminal("/api/items/import-direct/" + transferId)
                .get("status").asText()).isEqualTo("COMPLETED");
        assertThat(itemCount()).isEqualTo(1);
    }

    private static String tokenFor(String path, String body, String subject) throws Exception {
        HttpResponse<String> upload = upload(path, body, subject);
        assertThat(upload.statusCode()).isEqualTo(200);
        return MAPPER.readTree(upload.body()).get("token").asText();
    }

    private static HttpResponse<String> upload(String path, String body, String subject)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "text/csv")
                .header("Authorization", "Bearer " + jwt(subject))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> commit(String path, String token, String subject)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path + "/" + token + "/commit"))
                .header("Authorization", "Bearer " + jwt(subject))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode commitAndAwait(String path, String token, String subject)
            throws Exception {
        HttpResponse<String> response = commit(path, token, subject);
        assertThat(response.statusCode()).isEqualTo(202);
        String transferId = MAPPER.readTree(response.body()).get("transferId").asText();
        return awaitTerminal(path + "/" + transferId);
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + statusPath))
                    .header("Authorization", "Bearer " + jwt("importer"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            JsonNode status = MAPPER.readTree(response.body());
            String value = status.get("status").asText();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Import did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static long itemCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from items")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String jwt(String subject) throws Exception {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\",\"roles\":[\"IMPORTER\"],"
                + "\"aud\":\"https://review.example.com\",\"exp\":"
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
        Path home = Files.createTempDirectory("tesseraql-import-review-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: review-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      audience:
                        - https://review.example.com
                    policies:
                      items.write:
                        anyOf:
                          - role: IMPORTER
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"),
                "create table items (name varchar(100) primary key, qty integer not null);\n");

        writeImportRoute(home, "web/api/items/import", "items.import", "rollback", true);
        writeImportRoute(home, "web/api/items/import-lenient", "items.importLenient", "skip",
                true);
        writeImportRoute(home, "web/api/items/import-direct", "items.importDirect", "rollback",
                false);
        return home;
    }

    private static void writeImportRoute(Path home, String dir, String id, String onError,
            boolean review) throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  columns:
                    - name
                    - { name: qty, type: number }
                  onError: %s
                %ssteps:
                  - id: row
                    sql:
                      file: upsert-item.sql
                """.formatted(id, onError, review ? "  review: required\n" : ""));
        Files.writeString(route.resolve("upsert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                ;
                """);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
