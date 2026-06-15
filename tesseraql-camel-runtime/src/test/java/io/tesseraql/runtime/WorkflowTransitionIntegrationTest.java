package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.sql.PreparedStatement;
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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Acceptance test for the approval-workflow core (roadmap Phase 28 slice 1): a managed state machine
 * drives a business document through its transitions, and the app-mode duality keeps state in the
 * business table's column. A transition advances the state and appends history in one transaction;
 * an undeclared transition is a 409, a falsy guard a 422.
 */
@Testcontainers
class WorkflowTransitionIntegrationTest {

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
    void managedTransitionAdvancesStateAndRecordsHistory() throws Exception {
        assertThat(post("/purchase-requests/PR-1/submit").statusCode()).isEqualTo(200);
        assertThat(post("/purchase-requests/PR-1/approve").statusCode()).isEqualTo(200);

        assertThat(instanceState("purchase_request", "PR-1")).isEqualTo("approved");
        assertThat(column("purchase_requests", "last_action", "PR-1")).isEqualTo("approve");
        assertThat(historyCount("PR-1")).isEqualTo(2);
        assertThat(column("purchase_requests", "acted_by", "PR-1")).isEqualTo("approver-1");
    }

    @Test
    void falsyGuardIsUnprocessable() throws Exception {
        // PR-2 has amount 0, so the submit guard `document.amount > 0` rejects it.
        assertThat(post("/purchase-requests/PR-2/submit").statusCode()).isEqualTo(422);
        // The rejected transition rolls back entirely: no instance row, no business write — the
        // document stays effectively at its initial state with nothing persisted.
        assertThat(instanceState("purchase_request", "PR-2")).isNull();
        assertThat(column("purchase_requests", "last_action", "PR-2")).isNull();
    }

    @Test
    void illegalTransitionFromWrongStateIsConflict() throws Exception {
        // PR-3 is in draft; approve requires submitted.
        assertThat(post("/purchase-requests/PR-3/approve").statusCode()).isEqualTo(409);
        assertThat(historyCount("PR-3")).isZero();
    }

    @Test
    void appModeTransitionAdvancesTheBusinessColumn() throws Exception {
        assertThat(post("/expenses/EX-1/submit").statusCode()).isEqualTo(200);
        assertThat(column("expenses", "status", "EX-1")).isEqualTo("submitted");
        assertThat(post("/expenses/EX-1/approve").statusCode()).isEqualTo(200);
        assertThat(column("expenses", "status", "EX-1")).isEqualTo("approved");
    }

    private static HttpResponse<String> post(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token("approver-1"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String instanceState(String docType, String docId) throws Exception {
        return queryString("select current_state from tql_workflow_instance "
                + "where doc_type = ? and doc_id = ?", docType, docId);
    }

    private static String column(String table, String col, String id) throws Exception {
        return queryString("select " + col + " from " + table + " where id = ?", id);
    }

    private static int historyCount(String docId) throws Exception {
        return Integer.parseInt(queryString(
                "select count(*) from tql_workflow_history where doc_id = ?", docId));
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
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", sub, "roles", List.of("approver"))));
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
            statement.execute("create table purchase_requests (id varchar(64) primary key, "
                    + "title varchar(200), amount numeric(12,2) not null, "
                    + "last_action varchar(32), acted_by varchar(64))");
            statement.execute("insert into purchase_requests (id, title, amount) values "
                    + "('PR-1','Laptop',1000), ('PR-2','Pen',0), ('PR-3','Desk',500)");
            // App-mode: state lives in the status column, initialized to the initial state.
            statement.execute("create table expenses (id varchar(64) primary key, "
                    + "amount numeric(12,2) not null, status varchar(32) not null, "
                    + "note varchar(64))");
            statement.execute(
                    "insert into expenses (id, amount, status) values ('EX-1',200,'draft')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-workflow-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  workflow:
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

        Path workflowDir = home.resolve("workflow");
        Files.createDirectories(workflowDir);
        Files.writeString(workflowDir.resolve("purchase_request.yml"),
                """
                        version: tesseraql/v1
                        id: purchase_request
                        kind: workflow
                        document: { type: purchase_request, table: purchase_requests, key: id }
                        http: { basePath: /purchase-requests }
                        security: { auth: bearer }
                        initial: draft
                        states:
                          - { id: draft, type: initial }
                          - { id: submitted }
                          - { id: approved, type: terminal }
                          - { id: rejected, type: terminal }
                        transitions:
                          - { id: submit, from: draft, to: submitted, guard: "document.amount > 0", command: submit.sql }
                          - { id: approve, from: submitted, to: approved, command: approve.sql }
                          - { id: reject, from: submitted, to: rejected, command: reject.sql }
                        """);
        Files.writeString(workflowDir.resolve("submit.sql"), "update purchase_requests set "
                + "last_action = 'submit', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approve.sql"), "update purchase_requests set "
                + "last_action = 'approve', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("reject.sql"), "update purchase_requests set "
                + "last_action = 'reject', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");

        Files.writeString(workflowDir.resolve("expense.yml"),
                """
                        version: tesseraql/v1
                        id: expense
                        kind: workflow
                        mode: app
                        document: { type: expense, table: expenses, key: id, stateColumn: status }
                        http: { basePath: /expenses }
                        security: { auth: bearer }
                        initial: draft
                        states:
                          - { id: draft, type: initial }
                          - { id: submitted }
                          - { id: approved, type: terminal }
                        transitions:
                          - { id: submit, from: draft, to: submitted, guard: "document.amount > 0", command: ex_submit.sql }
                          - { id: approve, from: submitted, to: approved, command: ex_approve.sql }
                        """);
        Files.writeString(workflowDir.resolve("ex_submit.sql"),
                "update expenses set note = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("ex_approve.sql"),
                "update expenses set note = /* audit.user */ 'x' where id = /* key */ 'x'\n");
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
