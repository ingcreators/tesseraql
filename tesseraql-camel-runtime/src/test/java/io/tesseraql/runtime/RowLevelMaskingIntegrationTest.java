package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance test for row-level masking (roadmap Phase 29 slice 3): a query returns every row but a
 * field is masked in the rows outside the caller's scope. The scope is selected as a per-row flag
 * (the {@code as boolean} directive) and a field policy keys off it with {@code unmaskWhen} — no
 * per-row predicate evaluation in Java. A bypass role sees every value in clear; an unscoped caller
 * sees every value masked. The flag column is never emitted.
 */
@Testcontainers
class RowLevelMaskingIntegrationTest {

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
    void managerSeesOwnRegionInClearAndOthersMasked() throws Exception {
        JsonNode body = get(token("mgr", List.of(), List.of("R1")));
        assertThat(salary(body, 1)).isEqualTo("100"); // R1 row: in scope, plaintext
        assertThat(salary(body, 2)).isEqualTo("[MASKED]"); // R2 row: out of scope, masked
        // The internal scope-flag column is never exposed.
        assertThat(row(body, 1).has("_in_scope")).isFalse();
    }

    @Test
    void bypassRoleSeesEveryValueInClear() throws Exception {
        JsonNode body = get(token("admin", List.of("org-admin"), List.of()));
        assertThat(salary(body, 1)).isEqualTo("100");
        assertThat(salary(body, 2)).isEqualTo("200");
    }

    @Test
    void unscopedCallerSeesEveryValueMasked() throws Exception {
        JsonNode body = get(token("nobody", List.of(), List.of()));
        assertThat(salary(body, 1)).isEqualTo("[MASKED]");
        assertThat(salary(body, 2)).isEqualTo("[MASKED]");
    }

    private static JsonNode row(JsonNode body, int id) {
        return StreamSupport.stream(body.get("data").spliterator(), false)
                .filter(r -> r.get("id").asInt() == id).findFirst().orElseThrow();
    }

    private static String salary(JsonNode body, int id) {
        return row(body, id).get("salary").asText();
    }

    private static JsonNode get(String bearer) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/payroll"))
                        .header("Authorization", "Bearer " + bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private static String token(String sub, List<String> roles, List<String> regions)
            throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", sub, "roles", roles, "regions", regions)));
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
            statement.execute("create table payroll (id serial primary key, "
                    + "name varchar(80) not null, salary integer not null, "
                    + "region varchar(16) not null)");
            statement.execute("insert into payroll (name, salary, region) values "
                    + "('alice', 100, 'R1'), ('bob', 200, 'R2')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-masking-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      rolesClaim: roles
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));

        Files.createDirectories(home.resolve("scope"));
        Files.writeString(home.resolve("scope/payroll_region.sql"),
                "$.region in /* regions */ ('R1')\n");
        Files.writeString(home.resolve("scope/payroll_scope.yml"), """
                version: tesseraql/v1
                id: payroll_scope
                kind: scope
                match:
                  - when: { role: org-admin }
                    apply: all
                  - file: payroll_region.sql
                    params:
                      regions: principal.claim.regions
                """);

        Path dir = home.resolve("web/api/payroll");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: payroll.list
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                    fields:
                      salary:
                        mask: fixed
                        unmaskWhen: _in_scope
                """);
        Files.writeString(dir.resolve("list.sql"), """
                select id, name, salary, region,
                       /*%scope payroll_scope on o as boolean */ (1=1) as _in_scope
                from payroll o
                order by id
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
