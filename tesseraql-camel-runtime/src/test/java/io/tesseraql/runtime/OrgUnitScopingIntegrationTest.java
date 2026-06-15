package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.org.OrgUnitStore;
import io.tesseraql.core.org.OrgUnitStore.OrgUnit;
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
 * Acceptance test for the shared org-unit foundation (roadmap Phase 29 slice 2): a managed
 * {@code tql_org_unit} hierarchy with a maintained {@code tql_org_closure}, queried by a subtree
 * scope. A principal sees the rows of its org unit and everything below it; a leaf unit sees only
 * its own; a bypass role sees all; a principal with no unit claim sees none.
 *
 * <p>Tree: U1 ⊃ {U2 ⊃ {U3}, U4}. Orders 1..4 are owned by U1..U4 respectively.
 */
@Testcontainers
class OrgUnitScopingIntegrationTest {

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
        // The managed schema is provisioned at startup; seed the hierarchy and build the closure.
        OrgUnitStore store = orgUnitStore();
        store.upsert(new OrgUnit("U1", null, "Root", null));
        store.upsert(new OrgUnit("U2", "U1", "Sales", null));
        store.upsert(new OrgUnit("U3", "U2", "Sales East", null));
        store.upsert(new OrgUnit("U4", "U1", "Finance", null));
        store.rebuildClosure();
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

    private static OrgUnitStore orgUnitStore() {
        return runtime.camelContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.ORG_UNIT_STORE_BEAN, OrgUnitStore.class);
    }

    @Test
    void closureExposesEachSubtree() {
        assertThat(orgUnitStore().descendants(List.of("U2"))).containsExactlyInAnyOrder("U2", "U3");
        assertThat(orgUnitStore().descendants(List.of("U1")))
                .containsExactlyInAnyOrder("U1", "U2", "U3", "U4");
        assertThat(orgUnitStore().descendants(List.of("U3"))).containsExactly("U3");
    }

    @Test
    void rootUnitSeesTheWholeTree() throws Exception {
        assertThat(ids(get(token("mgr", List.of(), List.of("U1"))))).containsExactly(1, 2, 3, 4);
    }

    @Test
    void midUnitSeesItsSubtree() throws Exception {
        assertThat(ids(get(token("mgr", List.of(), List.of("U2"))))).containsExactly(2, 3);
    }

    @Test
    void leafUnitSeesOnlyItself() throws Exception {
        assertThat(ids(get(token("rep", List.of(), List.of("U3"))))).containsExactly(3);
    }

    @Test
    void bypassRoleSeesEverything() throws Exception {
        assertThat(ids(get(token("admin", List.of("org-admin"), List.of())))).containsExactly(1, 2,
                3, 4);
    }

    @Test
    void noUnitClaimSeesNothing() throws Exception {
        assertThat(ids(get(token("nobody", List.of(), List.of())))).isEmpty();
    }

    private static List<Integer> ids(JsonNode body) {
        return StreamSupport.stream(body.get("data").spliterator(), false)
                .map(row -> row.get("id").asInt()).sorted().toList();
    }

    private static JsonNode get(String bearer) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/orders"))
                        .header("Authorization", "Bearer " + bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private static String token(String sub, List<String> roles, List<String> orgUnits)
            throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", sub, "roles", roles, "org_unit", orgUnits)));
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
                    + "name varchar(80) not null, owner_unit varchar(64) not null)");
            statement.execute("insert into orders (name, owner_unit) values "
                    + "('a','U1'), ('b','U2'), ('c','U3'), ('d','U4')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-orgunit-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  orgunit:
                    mode: managed
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
        Files.writeString(home.resolve("scope/orders_subtree.sql"),
                "$.owner_unit in (select descendant_id from tql_org_closure\n"
                        + "                 where ancestor_id in /* my_units */ ('U1'))\n");
        Files.writeString(home.resolve("scope/orders_subtree.yml"), """
                version: tesseraql/v1
                id: orders_subtree
                kind: scope
                match:
                  - when: { role: org-admin }
                    apply: all
                  - file: orders_subtree.sql
                    params:
                      my_units: principal.claim.org_unit
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
                select id, name, owner_unit
                from orders o
                where /*%scope orders_subtree on o */ (1=1)
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
