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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The opt-in business-route audit log and per-app custom error pages (roadmap Phase 45): an
 * audited invocation lands in {@code tql_route_audit} with actor + declared (non-masked)
 * params and reads back through the scoped ops endpoint; a browser GET that fails renders the
 * app's {@code templates/errors/<status>.html} while API clients keep the JSON envelope.
 */
@Testcontainers
class RouteAuditAndErrorPagesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
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
    void anAuditedInvocationRecordsActorAndDeclaredParamsButNeverMaskedFields()
            throws Exception {
        HttpResponse<String> call = get("/api/things?q=widgets&secretCode=hunter2",
                token(List.of("OPS")), null);
        assertThat(call.statusCode()).isEqualTo(200);

        // The row is durable and carries who called what — with the declared param but
        // WITHOUT the masked field's value.
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.Statement statement = connection.createStatement();
                java.sql.ResultSet rs = statement.executeQuery(
                        "select * from tql_route_audit where route_id = 'things'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("actor")).isEqualTo("audit-caller");
            assertThat(rs.getString("http_method")).isEqualTo("GET");
            assertThat(rs.getInt("status")).isEqualTo(200);
            String params = rs.getString("params_json");
            assertThat(params).contains("widgets").doesNotContain("hunter2");
            assertThat(rs.getString("trace_id")).isNotBlank();
        }

        // The ops read surface serves it, scoped like every other per-app ops read.
        HttpResponse<String> ops = get("/_tesseraql/ops/audit", token(List.of("OPS")), null);
        assertThat(ops.statusCode()).isEqualTo(200);
        JsonNode rows = MAPPER.readTree(ops.body());
        assertThat(rows.toString()).contains("things").contains("audit-caller");

        // A caller without the ops.app grant for this app sees nothing (deny-by-default).
        HttpResponse<String> unscoped = get("/_tesseraql/ops/audit",
                token(List.of("OPS"), List.of("ops.app.other")), null);
        assertThat(unscoped.statusCode()).isEqualTo(200);
        assertThat(unscoped.body()).doesNotContain("audit-caller");
    }

    @Test
    void aBrowserErrorRendersTheCustomPageWhileApiClientsKeepJson() throws Exception {
        // The route's SQL is broken on purpose; a browser GET renders errors/500.html.
        HttpResponse<String> browser = get("/broken", null, "text/html");
        assertThat(browser.statusCode()).isEqualTo(500);
        assertThat(browser.headers().firstValue("Content-Type").orElse(""))
                .contains("text/html");
        assertThat(browser.body()).contains("Custom error page").contains("500");

        // An API client (no HTML Accept) keeps the machine-readable envelope.
        HttpResponse<String> api = get("/broken", null, null);
        assertThat(api.statusCode()).isEqualTo(500);
        assertThat(api.body()).contains("\"error\"");
    }

    private static HttpResponse<String> get(String path, String bearer, String accept)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (accept != null) {
            request.header("Accept", accept);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles) throws Exception {
        return token(roles, List.of("ops.app.*"));
    }

    private static String token(List<String> roles, List<String> permissions) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(Map.of(
                "sub", "audit-caller", "preferred_username", "audit-caller",
                "roles", roles, "permissions", permissions)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-audit-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: audit-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      rolesClaim: roles
                      permissionsClaim: permissions
                    policies:
                      ops.batch.view:
                        anyOf:
                          - role: OPS
                      things.read:
                        anyOf:
                          - role: OPS
                  audit:
                    routes:
                      enabled: true
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path things = target.resolve("web/api/things");
        Files.createDirectories(things);
        Files.writeString(things.resolve("get.yml"), """
                version: tesseraql/v1
                id: things
                kind: route
                recipe: query-json
                input:
                  q:
                    type: string
                    required: false
                  secretCode:
                    type: string
                    required: false
                    classification: secret
                security:
                  auth: bearer
                  policy: things.read
                sql:
                  file: things.sql
                  mode: query
                  params:
                    q: query.q
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(things.resolve("things.sql"),
                "select /* q */ 'x' as q_echo, 1 as value\n");

        Path broken = target.resolve("web/broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("get.yml"), """
                version: tesseraql/v1
                id: broken
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: broken.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(broken.resolve("broken.sql"),
                "select * from no_such_table_anywhere\n");

        Path errors = target.resolve("templates/errors");
        Files.createDirectories(errors);
        Files.writeString(errors.resolve("500.html"), """
                <!DOCTYPE html>
                <html><body>
                <h1>Custom error page</h1>
                <p>Status: <span th:text="${status}">0</span></p>
                </body></html>
                """);
        return target;
    }
}
