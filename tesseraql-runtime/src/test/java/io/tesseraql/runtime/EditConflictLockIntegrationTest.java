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
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance test for the declared lock (docs/edit-conflict.md slice 1): a route declaring
 * {@code lock:} refuses a stale write with {@code TQL-SQL-4094} and the affordance beside the
 * conflict, a deliberate waiver lands, and the hand-authored {@code expect:} shape in the same
 * boot keeps answering {@code TQL-SQL-4092} untouched.
 *
 * <p>Ordered, because the cases are sequential writes against one row.
 */
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
class EditConflictLockIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
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

    @Test
    @Order(1)
    void aSaveCarryingTheCurrentLockAppliesAndTheStatementAdvancesTheColumn() throws Exception {
        HttpResponse<String> ok = postJson("/api/items/update",
                "{\"id\": 1, \"name\": \"first edit\", \"_lock\": 1}", "k-1");

        assertThat(ok.statusCode()).as(ok::body).isEqualTo(200);
        assertThat(versionOf(1)).isEqualTo(2);
        assertThat(nameOf(1)).isEqualTo("first edit");
    }

    @Test
    @Order(2)
    void aStaleLockIsRefusedWithTheAffordanceBesideTheConflict() throws Exception {
        HttpResponse<String> stale = postJson("/api/items/update",
                "{\"id\": 1, \"name\": \"lost update\", \"_lock\": 1}", "k-2");

        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        JsonNode error = MAPPER.readTree(stale.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-SQL-4094");
        assertThat(error.path("details").path("conflict").path("actualRows").asInt()).isZero();
        assertThat(error.path("details").path("conflict").path("hint").asText())
                .contains("another user");
        JsonNode lock = error.path("details").path("lock");
        assertThat(lock.path("column").asText()).isEqualTo("version");
        assertThat(lock.path("field").asText()).isEqualTo("_lock");
        assertThat(lock.path("overwriteField").asText()).isEqualTo("_overwrite");
        // The stale write did not stick.
        assertThat(nameOf(1)).isEqualTo("first edit");
    }

    @Test
    @Order(3)
    void neitherLockFieldIsRefusedBeforeTheStatementRuns() throws Exception {
        HttpResponse<String> bare = postJson("/api/items/update",
                "{\"id\": 1, \"name\": \"no lock\"}", "k-3");

        assertThat(bare.statusCode()).as(bare::body).isEqualTo(400);
        assertThat(MAPPER.readTree(bare.body()).path("error").path("code").asText())
                .isEqualTo("TQL-FIELD-2011");
        assertThat(nameOf(1)).isEqualTo("first edit");
    }

    @Test
    @Order(4)
    void aDeliberateWaiverLandsOverWhateverIsThere() throws Exception {
        // Two invariants in one press. The waiver is the whole point of reserving _overwrite: an
        // unreserved field would answer 400 TQL-FIELD-2002 here, before the lock is ever read.
        // And it re-posts under the same idempotency key the stale save spent, which only works
        // because that conflict was thrown rather than rendered - a rendered answer would have
        // been stored as that key's replayable response (decision 5).
        HttpResponse<String> waived = postJson("/api/items/update",
                "{\"id\": 1, \"name\": \"overwritten\", \"_lock\": 1, \"_overwrite\": \"1\"}",
                // The SAME key the stale save spent.
                "k-2");

        assertThat(waived.statusCode()).as(waived::body).isEqualTo(200);
        assertThat(nameOf(1)).isEqualTo("overwritten");
        assertThat(versionOf(1)).isEqualTo(3);
    }

    @Test
    @Order(5)
    void aWaiverStillRefusesWhenTheRowItselfIsGone() throws Exception {
        // The waiver expands the lock predicate and nothing else, so every other predicate in the
        // author's WHERE still stands — a deleted row refuses again (decision 7).
        HttpResponse<String> missing = postJson("/api/items/update",
                "{\"id\": 999, \"name\": \"ghost\", \"_overwrite\": \"1\"}", "k-5");

        assertThat(missing.statusCode()).as(missing::body).isEqualTo(409);
        assertThat(MAPPER.readTree(missing.body()).path("error").path("code").asText())
                .isEqualTo("TQL-SQL-4094");
    }

    @Test
    @Order(6)
    void aFormPostTypesItsLockTheSameWayTheJsonPostDoes() throws Exception {
        HttpResponse<String> stale = postForm("/api/items/update",
                "id=1&name=form+edit&_lock=999");
        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);

        HttpResponse<String> ok = postForm("/api/items/update", "id=1&name=form+edit&_lock=3");
        assertThat(ok.statusCode()).as(ok::body).isEqualTo(200);
        assertThat(nameOf(1)).isEqualTo("form edit");
    }

    @Test
    @Order(7)
    void theHandAuthoredExpectShapeInTheSameBootIsUntouched() throws Exception {
        // 4092 keeps its published meaning, and carries no lock affordance: a client must be able
        // to tell a declared lock — which has a waiver — from any other row-count expectation.
        HttpResponse<String> stale = postJson("/api/items/legacy-update",
                "{\"id\": 2, \"name\": \"stale\", \"version\": 99}", "k-7");

        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        JsonNode error = MAPPER.readTree(stale.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-SQL-4092");
        assertThat(error.path("details").has("lock")).isFalse();
    }

    @Test
    @Order(8)
    void anHtmxSaveGetsTheConflictDialogRetargetedAtTheShellHost() throws Exception {
        HttpResponse<String> stale = postForm("/api/items/formUpdate",
                "id=2&name=htmx+edit&_lock=999",
                Map.of("HX-Request", "true", "HX-Trigger", "items-edit-form",
                        "Accept", "text/html"));

        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        assertThat(stale.headers().firstValue("HX-Retarget"))
                .contains("[data-tql-conflict-host]");
        assertThat(stale.headers().firstValue("HX-Reswap")).contains("innerHTML");
        assertThat(stale.body()).contains("data-tql-conflict-dialog")
                .contains("form=\"items-edit-form\"")
                .contains("name=\"_overwrite\"")
                // Reload goes where a successful save would have gone.
                .contains("href=\"/api/items/2\"");
    }

    @Test
    @Order(9)
    void aSaveWithoutJavaScriptGetsAPageInsteadOfARawEnvelope() throws Exception {
        HttpResponse<String> stale = postForm("/api/items/formUpdate",
                "id=2&name=plain+edit&_lock=999", Map.of("Accept", "text/html"));

        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        assertThat(stale.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/html");
        assertThat(stale.body()).contains("<title>")
                .contains("name=\"name\"").contains("plain edit")
                .contains("name=\"_overwrite\"")
                // The same destination the dialog face offers.
                .contains("href=\"/api/items/2\"")
                .doesNotContain("name=\"_lock\"");
    }

    @Test
    @Order(10)
    void theOverwriteThatPageOffersLands() throws Exception {
        HttpResponse<String> waived = postForm("/api/items/formUpdate",
                "id=2&name=plain+edit&_overwrite=1", Map.of("Accept", "text/html"));

        // The route redirects on success, so a browser leg answers 303 rather than 200.
        assertThat(waived.statusCode()).as(waived::body).isIn(200, 303);
        assertThat(nameOf(2)).isEqualTo("plain edit");
    }

    private static HttpResponse<String> postJson(String path, String body, String key)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String body) throws Exception {
        return postForm(path, body, Map.of());
    }

    private static HttpResponse<String> postForm(String path, String body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                TestClaims.addressed(Map.of("sub", "editor"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table items (id int primary key, "
                    + "name varchar(80) not null, version int not null)");
            statement.execute("insert into items (id, name, version) values (1,'a',1), (2,'b',1)");
        }
    }

    private static int versionOf(int id) throws Exception {
        return column(id, "version", java.sql.ResultSet::getInt);
    }

    private static String nameOf(int id) throws Exception {
        return column(id, "name", java.sql.ResultSet::getString);
    }

    private interface Reader<T> {
        T read(java.sql.ResultSet rows, int index) throws java.sql.SQLException;
    }

    private static <T> T column(int id, String name, Reader<T> reader) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "select " + name + " from items where id = ?")) {
            statement.setInt(1, id);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                rows.next();
                return reader.read(rows, 1);
            }
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-lock-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: edit-conflict-lock
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      audience: https://app.example.com
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));

        Path updateDir = home.resolve("web/api/items/update");
        Files.createDirectories(updateDir);
        // idempotency: is declared on purpose — the waiver re-posts under the caller's own key,
        // and it only works because the conflict is thrown rather than rendered (decision 5).
        Files.writeString(updateDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.update
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                # The block form declares the column's type: a form value always arrives as a
                # string, so an opaque lock could not be compared against an integer column.
                lock: { column: version, type: integer }
                idempotency:
                  required: false
                input:
                  id: { type: integer, required: true }
                  name: { type: string, required: true }
                steps:
                  - id: main
                    sql:
                      file: update.sql
                      params:
                        id: params.id
                        name: params.name
                response:
                  json:
                    status: 200
                    body:
                      affected: steps.main.affectedRows
                """);
        Files.writeString(updateDir.resolve("update.sql"), """
                update items
                   set name = /* name */ 'x',
                       version = version + 1
                 where id = /* id */ 0
                   and /*%lock*/ (1=1)
                """);

        // A locked route that declares a redirect, so the conflict answer's Reload choice has a
        // destination — it goes where a successful save would have gone.
        Path formDir = home.resolve("web/api/items/formUpdate");
        Files.createDirectories(formDir);
        Files.writeString(formDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.formUpdate
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                lock: { column: version, type: integer }
                input:
                  id: { type: integer, required: true }
                  name: { type: string, required: true }
                steps:
                  - id: main
                    sql:
                      file: update.sql
                      params:
                        id: params.id
                        name: params.name
                response:
                  redirect:
                    location: /api/items/{params.id}
                """);
        Files.writeString(formDir.resolve("update.sql"), """
                update items
                   set name = /* name */ 'x',
                       version = version + 1
                 where id = /* id */ 0
                   and /*%lock*/ (1=1)
                """);

        // The hand-authored shape in the same boot: expect: plus an author-written predicate.
        Path legacyDir = home.resolve("web/api/items/legacy-update");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.legacyUpdate
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                input:
                  id: { type: integer, required: true }
                  name: { type: string, required: true }
                  version: { type: integer, required: true }
                steps:
                  - id: main
                    sql:
                      file: legacy.sql
                      expect:
                        rowCount: 1
                      params:
                        id: params.id
                        name: params.name
                        version: params.version
                response:
                  json:
                    status: 200
                    body:
                      affected: steps.main.affectedRows
                """);
        Files.writeString(legacyDir.resolve("legacy.sql"), """
                update items
                   set name = /* name */ 'x',
                       version = version + 1
                 where id = /* id */ 0
                   and version = /* version */ 0
                """);
        return home;
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
}
