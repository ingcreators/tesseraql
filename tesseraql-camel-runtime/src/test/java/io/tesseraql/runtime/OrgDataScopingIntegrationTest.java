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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Acceptance test for organizational data scoping (roadmap Phase 29): the same query, run by
 * principals with different roles/claims, returns only each caller's rows. A bypass role sees all,
 * an unscoped caller sees none, and roles compose additively (OR) - all from one {@code /*%scope%/}
 * directive resolved against the request principal.
 */
@Testcontainers
class OrgDataScopingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void bypassRoleSeesEveryRow() throws Exception {
        assertThat(ids(get(token("admin", List.of("org-admin"), List.of(), List.of()))))
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void managerSeesOnlyTheirRegions() throws Exception {
        assertThat(ids(get(token("mgr", List.of("region-manager"), List.of(), List.of("R1")))))
                .containsExactly(1, 2);
    }

    @Test
    void repSeesOnlyTheirOwnRows() throws Exception {
        assertThat(ids(get(token("u001", List.of(), List.of("orders:read-own"), List.of()))))
                .containsExactly(1, 3);
    }

    @Test
    void matchingRolesComposeAdditively() throws Exception {
        // region-manager for R2 (ids 3,4) OR read-own as u001 (ids 1,3) = 1,3,4.
        assertThat(ids(get(token("u001", List.of("region-manager"),
                List.of("orders:read-own"), List.of("R2"))))).containsExactly(1, 3, 4);
    }

    @Test
    void unscopedCallerSeesNothing() throws Exception {
        assertThat(ids(get(token("nobody", List.of(), List.of(), List.of())))).isEmpty();
    }

    private static List<Integer> ids(JsonNode body) {
        return StreamSupport.stream(body.get("data").spliterator(), false)
                .map(row -> row.get("id").asInt()).sorted().toList();
    }

    private static JsonNode get(String bearer) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/orders"))
                        .header("Authorization", "Bearer " + bearer)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private static String token(String sub, List<String> roles, List<String> permissions,
            List<String> regions) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(Map.of(
                "sub", sub, "roles", roles, "permissions", permissions, "regions", regions)));
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
            statement.execute("create table orders (id serial primary key, "
                    + "name varchar(80) not null, region varchar(16) not null, "
                    + "created_by varchar(64) not null)");
            statement.execute("insert into orders (name, region, created_by) values "
                    + "('a','R1','u001'), ('b','R1','u999'), "
                    + "('c','R2','u001'), ('d','R2','u777')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-scope-it");
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
                      permissionsClaim: permissions
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));

        Files.createDirectories(home.resolve("scope"));
        Files.writeString(home.resolve("scope/by_region.sql"),
                "$.region in /* regions */ ('R1')\n");
        Files.writeString(home.resolve("scope/own_rows.sql"),
                "$.created_by = /* uid */ 'u'\n");
        Files.writeString(home.resolve("scope/orders_scope.yml"), """
                version: tesseraql/v1
                id: orders_scope
                kind: scope
                match:
                  - when: { role: org-admin }
                    apply: all
                  - when: { role: region-manager }
                    file: by_region.sql
                    params:
                      regions: principal.claim.regions
                  - when: { permission: orders:read-own }
                    file: own_rows.sql
                    params:
                      uid: principal.subject
                """);

        Path ordersDir = home.resolve("web/api/orders");
        Files.createDirectories(ordersDir);
        Files.writeString(ordersDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.list
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
                """);
        Files.writeString(ordersDir.resolve("list.sql"), """
                select id, name, region, created_by
                from orders o
                where /*%scope orders_scope on o */ (1=1)
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
