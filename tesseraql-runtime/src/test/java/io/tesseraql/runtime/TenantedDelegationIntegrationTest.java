package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
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
 * Delegation under shared-schema tenancy (docs/delegation.md, docs/multi-tenancy.md): the three
 * assignee funnels ask the same question under the same tenant. An approver's absence rule lives
 * under their tenant; a request resolved to that tenant lands its task with the delegate, and the
 * sweeper's fallback reassignment honours the fallback's own rule under the task's tenant.
 *
 * <p>Neither held before docs/audit-low-leads.md slice 2b (G37): the transition funnel derived the
 * tenant id as {@code String.valueOf(TenantContext)} — the record's {@code toString} — so the rule
 * was looked up under {@code TenantContext[id=acme, …]} and never found, and that string is what
 * the instance and the task persisted as their {@code tenant_id}; the sweeper passed {@code null}
 * and read the untenanted scope.
 */
@Testcontainers
class TenantedDelegationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final tools.jackson.databind.ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers
            .constrained();
    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String TENANT = "acme";

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
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void theAssignFunnelHonoursTheRuleUnderTheRequestsTenantAndPersistsThatTenant()
            throws Exception {
        putRule(TENANT, "approver-1", "deputy-1");
        try {
            assertThat(transition("PR-1", "submit", "requester-1").statusCode()).isEqualTo(200);
            Map<String, String> task = taskRow("PR-1");
            assertThat(task.get("assignee")).isEqualTo("deputy-1");
            assertThat(task.get("delegated_from")).isEqualTo("approver-1");
            // The tenant id itself, not the resolved context's toString.
            assertThat(task.get("tenant_id")).isEqualTo(TENANT);
            assertThat(queryString("select tenant_id from tql_workflow_instance "
                    + "where doc_type = 'purchase_request' and doc_id = ?", "PR-1"))
                    .isEqualTo(TENANT);
            // The delegate acts as themselves (and the task leaves the sweeper's queue).
            assertThat(transition("PR-1", "approve", "deputy-1").statusCode()).isEqualTo(200);
            assertThat(taskRow("PR-1").get("status")).isEqualTo("DONE");
        } finally {
            clearRule(TENANT, "approver-1");
        }
    }

    @Test
    void theSweepersFallbackHonoursTheRuleUnderTheTasksTenant() throws Exception {
        // The fallback approver is absent, covered by deputy-2 — a rule under the tenant.
        putRule(TENANT, "fallback-1", "deputy-2");
        try {
            assertThat(transition("PR-2", "submit", "requester-1").statusCode()).isEqualTo(200);
            assertThat(taskRow("PR-2").get("assignee")).isEqualTo("approver-1");

            WorkflowSweeper sweeper = runtime.context().lookup(
                    TesseraqlProperties.WORKFLOW_SWEEPER_BEAN, WorkflowSweeper.class);
            assertThat(sweeper.sweep()).isEqualTo(1);
            Map<String, String> task = taskRow("PR-2");
            assertThat(task.get("assignee")).isEqualTo("deputy-2");
            assertThat(task.get("delegated_from")).isEqualTo("fallback-1");
            assertThat(task.get("tenant_id")).isEqualTo(TENANT);
        } finally {
            clearRule(TENANT, "fallback-1");
        }
    }

    private static void putRule(String tenant, String subject, String delegate) {
        runtime.context().lookup(
                TesseraqlProperties.DELEGATION_STORE_BEAN,
                io.tesseraql.core.workflow.DelegationStore.class)
                .put(tenant, subject, delegate, Instant.now().minusSeconds(60),
                        Instant.now().plusSeconds(3600));
    }

    private static void clearRule(String tenant, String subject) {
        runtime.context().lookup(
                TesseraqlProperties.DELEGATION_STORE_BEAN,
                io.tesseraql.core.workflow.DelegationStore.class).clear(tenant, subject);
    }

    private static HttpResponse<String> transition(String key, String id, String actor)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/purchase-requests/" + key + "/" + id))
                .header("Authorization", "Bearer " + token(actor))
                .header("X-Tenant-Id", TENANT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> taskRow(String docId) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = connection.prepareStatement(
                        "select assignee, delegated_from, status, tenant_id "
                                + "from tql_workflow_task where doc_id = ? "
                                + "order by created_at desc limit 1")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Map<String, String> row = new java.util.HashMap<>();
                row.put("assignee", rs.getString(1));
                row.put("delegated_from", rs.getString(2));
                row.put("status", rs.getString(3));
                row.put("tenant_id", rs.getString(4));
                return row;
            }
        }
    }

    private static String queryString(String sql, String... args) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String token(String sub) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", sub, "roles", List.of("approver")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table purchase_requests (id varchar(64) primary key, "
                    + "tenant_id varchar(64) not null, amount numeric(12,2) not null, "
                    + "last_action varchar(32))");
            statement.execute("insert into purchase_requests (id, tenant_id, amount) values "
                    + "('PR-1', 'acme', 100), ('PR-2', 'acme', 100)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-tenanted-delegation-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tenancy:
                  enabled: true
                  mode: shared-schema
                  required: true
                  resolver:
                    type: header
                    source: X-Tenant-Id

                tesseraql:
                  app:
                    name: tenanted-delegation
                  workflow:
                    mode: managed
                    sweep:
                      interval: 1h
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      audience: https://app.example.com
                      rolesClaim: roles
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));
        Path workflowDir = home.resolve("workflow");
        Files.createDirectories(workflowDir);
        Files.writeString(workflowDir.resolve("purchase_request.yml"), """
                version: tesseraql/v1
                id: purchase_request
                kind: workflow
                document: { type: purchase_request, table: purchase_requests, key: id }
                basePath: /purchase-requests
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: submitted }
                  - { id: approved, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: submitted
                    command: { file: submit.sql }
                    assign: { file: approver.sql }
                  - { id: approve, from: submitted, to: approved, command: { file: approve.sql } }
                deadlines:
                  - state: submitted
                    within: 0s
                    onBreach: { reassign: { file: fallback.sql } }
                """);
        Files.writeString(workflowDir.resolve("submit.sql"), "update purchase_requests set "
                + "last_action = 'submit' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approve.sql"), "update purchase_requests set "
                + "last_action = 'approve' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approver.sql"),
                "select 'approver-1' as assignee\n");
        Files.writeString(workflowDir.resolve("fallback.sql"),
                "select 'fallback-1' as assignee\n");
        return home;
    }
}
