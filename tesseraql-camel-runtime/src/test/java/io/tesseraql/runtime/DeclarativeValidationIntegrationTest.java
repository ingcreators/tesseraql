package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 19 acceptance (roadmap "declarative validation"): a unique-email SQL rule and a
 * cross-field expression rule guard a command route. A violating request is rejected with a
 * field-scoped 422 — TQL-FIELD-4220 with rule id, field path, rule code, and message key — as
 * JSON and as an inline error fragment for htmx, all violations are collected in one response,
 * the rules run inside the command's transaction so nothing is written, and a valid request
 * commits normally.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeclarativeValidationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
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
    }

    @Test
    @Order(1)
    void validRequestPassesEveryRuleAndCommits() throws Exception {
        HttpResponse<String> response = post("/api/members", """
                {"email": "new@example.com", "name": "Suzuki",
                 "startDate": "2026-01-01", "endDate": "2026-12-31"}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(201);
        long memberId = MAPPER.readTree(response.body()).path("memberId").asLong();
        assertThat(memberId).isPositive();
        Map<String, Object> member = queryOne(
                "select email, created_by from members where id = " + memberId);
        assertThat(member.get("email")).isEqualTo("new@example.com");
        assertThat(member.get("created_by")).isEqualTo("admin"); // /* audit.user */
    }

    @Test
    @Order(2)
    void duplicateEmailIsRejectedWithAFieldScoped422AndNothingIsWritten() throws Exception {
        long membersBefore = count();
        HttpResponse<String> response = post("/api/members", """
                {"email": "taken@example.com", "name": "Tanaka",
                 "startDate": "2026-01-01", "endDate": "2026-12-31"}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = MAPPER.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-FIELD-4220");
        assertThat(error.path("message").asText()).isEqualTo("Unprocessable Entity");
        JsonNode field = error.path("fields").get(0);
        assertThat(field.path("rule").asText()).isEqualTo("uniqueEmail");
        assertThat(field.path("field").asText()).isEqualTo("email");
        assertThat(field.path("code").asText()).isEqualTo("duplicate");
        // The declared key rides as messageKey (roadmap Phase 22); message carries the
        // localized text, here the built-in tql.constraint.duplicate English fallback.
        assertThat(field.path("messageKey").asText()).isEqualTo("members.email.duplicate");
        assertThat(field.path("message").asText()).isEqualTo("Already exists.");

        assertThat(count()).isEqualTo(membersBefore); // the rule ran before the insert
    }

    @Test
    @Order(3)
    void everyRuleRunsAndAllViolationsComeBackInOneResponse() throws Exception {
        HttpResponse<String> response = post("/api/members", """
                {"email": "taken@example.com", "name": "Tanaka",
                 "startDate": "2026-12-31", "endDate": "2026-01-01"}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode fields = MAPPER.readTree(response.body()).path("error").path("fields");
        assertThat(fields).hasSize(2);
        assertThat(List.of(fields.get(0).path("rule").asText(),
                fields.get(1).path("rule").asText()))
                .containsExactlyInAnyOrder("uniqueEmail", "dateOrder");
    }

    @Test
    @Order(4)
    void htmxRequestReceivesAnInlineErrorFragment() throws Exception {
        HttpResponse<String> response = post("/api/members", """
                {"email": "taken@example.com", "name": "Tanaka",
                 "startDate": "2026-01-01", "endDate": "2026-12-31"}""",
                Map.of("HX-Request", "true"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/html");
        assertThat(response.body()).contains("class=\"hc-alert\" data-variant=\"error\"")
                .contains("data-hc-field-errors")
                .contains("data-error-code=\"TQL-FIELD-4220\"")
                .contains("hc-alert__error")
                .contains("data-field=\"email\"")
                .contains("data-code=\"duplicate\"")
                .contains("data-message-key=\"members.email.duplicate\"");
    }

    private static HttpResponse<String> post(String path, String json,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        extraHeaders.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin", "roles",
                        List.of("USER_WRITE"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Map<String, Object> queryOne(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                row.put(rs.getMetaData().getColumnLabel(col).toLowerCase(java.util.Locale.ROOT),
                        rs.getObject(col));
            }
            return row;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long count() {
        Object value = queryOne("select count(*) as c from members").get("c");
        return ((Number) value).longValue();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            // Dates are ISO-8601 strings: the cross-field rule compares them lexicographically.
            statement.execute("""
                    create table members (
                      id serial primary key,
                      email varchar(320) not null,
                      name varchar(200) not null,
                      start_date varchar(10),
                      end_date varchar(10),
                      created_by varchar(100),
                      created_at timestamp)""");
            statement.execute("insert into members (email, name) "
                    + "values ('taken@example.com', 'Sato')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-validation-it");
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        writeMemberRoute(target);
        return target;
    }

    /** The acceptance slice: a registration command guarded by a SQL and an expression rule. */
    private static void writeMemberRoute(Path appHome) throws IOException {
        Path route = appHome.resolve("web/api/members");
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                input:
                  email:
                    type: string
                    required: true
                  name:
                    type: string
                    required: true
                  startDate:
                    type: string
                  endDate:
                    type: string
                security:
                  auth: bearer
                  policy: users.write
                validate:
                  uniqueEmail:
                    file: check-email.sql
                    params:
                      email: body.email
                    field: email
                    code: duplicate
                    message: members.email.duplicate
                  dateOrder:
                    when: body.endDate != null
                    rule: body.endDate >= body.startDate
                    field: endDate
                    code: end-before-start
                    message: members.dates.end-before-start
                sql:
                  file: insert-member.sql
                  mode: update
                  keys: [id]
                  params:
                    email: body.email
                    name: body.name
                    startDate: body.startDate
                    endDate: body.endDate
                response:
                  json:
                    status: 201
                    body:
                      memberId: sql.keys.id
                """);
        Files.writeString(route.resolve("check-email.sql"), """
                select
                  'email' as field
                from
                  members
                where
                  email = /* email */'taken@example.com'
                """);
        Files.writeString(route.resolve("insert-member.sql"), """
                insert into members (email, name, start_date, end_date, created_by, created_at)
                values (/* email */'new@example.com', /* name */'Suzuki',
                        /* startDate */'2026-01-01', /* endDate */'2026-12-31',
                        /* audit.user */'someone', /* audit.now */'2026-01-01 00:00:00')
                """);
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
