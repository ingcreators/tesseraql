package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance test for the approval workflow (roadmap Phase 28 slices 1–3): a managed state machine
 * drives a document through transitions; a transition resolves assignees and opens a deadline-bearing
 * task; a document with open tasks may only be transitioned by someone who holds one; the cluster-safe
 * sweeper escalates an overdue task exactly once; and the assignee may delegate to another principal.
 */
@Testcontainers
class WorkflowTransitionIntegrationTest {

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
    void managedTransitionAdvancesStateAndRecordsHistory() throws Exception {
        assertThat(post("/purchase-requests/PR-1/submit", "approver-1").statusCode())
                .isEqualTo(200);
        assertThat(post("/purchase-requests/PR-1/approve", "approver-1").statusCode())
                .isEqualTo(200);

        assertThat(instanceState("purchase_request", "PR-1")).isEqualTo("approved");
        assertThat(column("purchase_requests", "last_action", "PR-1")).isEqualTo("approve");
        assertThat(historyCount("PR-1")).isEqualTo(2);
        assertThat(column("purchase_requests", "acted_by", "PR-1")).isEqualTo("approver-1");
    }

    @Test
    void submitOpensATaskForTheResolvedAssignee() throws Exception {
        assertThat(post("/purchase-requests/PR-4/submit", "requester-1").statusCode())
                .isEqualTo(200);
        assertThat(taskCount("PR-4", "OPEN")).isEqualTo(1);
        assertThat(openTaskAssignee("PR-4")).isEqualTo("approver-1");

        assertThat(post("/purchase-requests/PR-4/approve", "approver-1").statusCode())
                .isEqualTo(200);
        assertThat(taskCount("PR-4", "OPEN")).isZero();
        assertThat(taskCount("PR-4", "DONE")).isEqualTo(1);
    }

    @Test
    void nonAssigneeIsForbidden() throws Exception {
        assertThat(post("/purchase-requests/PR-5/submit", "requester-1").statusCode())
                .isEqualTo(200);
        assertThat(post("/purchase-requests/PR-5/approve", "intruder").statusCode()).isEqualTo(403);
        assertThat(instanceState("purchase_request", "PR-5")).isEqualTo("submitted");
        assertThat(taskCount("PR-5", "OPEN")).isEqualTo(1);
    }

    @Test
    void falsyGuardIsUnprocessable() throws Exception {
        assertThat(post("/purchase-requests/PR-2/submit", "requester-1").statusCode())
                .isEqualTo(422);
        assertThat(instanceState("purchase_request", "PR-2")).isNull();
        assertThat(taskCount("PR-2", "OPEN")).isZero();
        assertThat(column("purchase_requests", "last_action", "PR-2")).isNull();
    }

    @Test
    void illegalTransitionFromWrongStateIsConflict() throws Exception {
        assertThat(post("/purchase-requests/PR-3/approve", "approver-1").statusCode())
                .isEqualTo(409);
        assertThat(historyCount("PR-3")).isZero();
    }

    @Test
    void appModeTransitionAdvancesTheBusinessColumn() throws Exception {
        assertThat(post("/expenses/EX-1/submit", "requester-1").statusCode()).isEqualTo(200);
        assertThat(column("expenses", "status", "EX-1")).isEqualTo("submitted");
        assertThat(post("/expenses/EX-1/approve", "approver-1").statusCode()).isEqualTo(200);
        assertThat(column("expenses", "status", "EX-1")).isEqualTo("approved");
    }

    @Test
    void delegationReassignsToTheDelegateWhoCanThenAct() throws Exception {
        assertThat(post("/purchase-requests/PR-6/submit", "requester-1").statusCode())
                .isEqualTo(200);
        assertThat(openTaskAssignee("PR-6")).isEqualTo("approver-1");

        // The current assignee delegates the task; the delegate now holds it.
        assertThat(post("/purchase-requests/PR-6/delegate/delegate-1", "approver-1").statusCode())
                .isEqualTo(200);
        assertThat(openTaskAssignee("PR-6")).isEqualTo("delegate-1");

        // The original assignee can no longer act; the delegate can.
        assertThat(post("/purchase-requests/PR-6/approve", "approver-1").statusCode())
                .isEqualTo(403);
        assertThat(post("/purchase-requests/PR-6/approve", "delegate-1").statusCode())
                .isEqualTo(200);
    }

    @Test
    void onlyTheAssigneeMayDelegate() throws Exception {
        assertThat(post("/purchase-requests/PR-7/submit", "requester-1").statusCode())
                .isEqualTo(200);
        assertThat(post("/purchase-requests/PR-7/delegate/elsewhere", "intruder").statusCode())
                .isEqualTo(403);
        assertThat(openTaskAssignee("PR-7")).isEqualTo("approver-1");
    }

    @Test
    void deadlineIsSetAndTheSweeperEscalatesOverdueTaskExactlyOnce() throws Exception {
        assertThat(post("/escalating-requests/ER-1/submit", "requester-1").statusCode())
                .isEqualTo(200);
        // The 'submitted' state has a deadline, so the opened task carries a due_at.
        assertThat(queryString("select due_at from tql_workflow_task "
                + "where doc_id = ? and status = 'OPEN'", "ER-1")).isNotNull();

        // The cluster-safe sweeper escalates the overdue task to the onBreach.reassign resolver.
        WorkflowSweeper sweeper = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.WORKFLOW_SWEEPER_BEAN, WorkflowSweeper.class);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(openTaskAssignee("ER-1")).isEqualTo("dept-head-1");
        assertThat(queryString("select due_at from tql_workflow_task "
                + "where doc_id = ? and status = 'OPEN'", "ER-1")).isNull();
        assertThat(escalateHistoryCount("ER-1")).isEqualTo(1);
        // The escalation enqueued a reminder notification on the outbox (Phase 20 channels).
        assertThat(notificationCount("ER-1")).isEqualTo(1);

        // Exactly once: the cleared deadline means a second sweep escalates and notifies nothing.
        assertThat(sweeper.sweep()).isZero();
        assertThat(escalateHistoryCount("ER-1")).isEqualTo(1);
        assertThat(notificationCount("ER-1")).isEqualTo(1);
    }

    @Test
    void onBreachEscalateAutoFiresTheTransitionAsSystem() throws Exception {
        assertThat(post("/auto-requests/AU-1/submit", "requester-1").statusCode()).isEqualTo(200);
        assertThat(instanceState("auto_request", "AU-1")).isEqualTo("submitted");
        assertThat(taskCount("AU-1", "OPEN")).isEqualTo(1);

        // The deadline breach auto-fires the approve transition as the system: the state advances,
        // the command runs (audit.user = system), the task completes, and history records it.
        WorkflowSweeper sweeper = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.WORKFLOW_SWEEPER_BEAN, WorkflowSweeper.class);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(instanceState("auto_request", "AU-1")).isEqualTo("approved");
        assertThat(column("auto_requests", "last_action", "AU-1")).isEqualTo("approve");
        assertThat(column("auto_requests", "acted_by", "AU-1")).isEqualTo("system");
        assertThat(taskCount("AU-1", "OPEN")).isZero();
        assertThat(systemHistoryCount("AU-1", "approve")).isEqualTo(1);

        // Exactly once: the task is completed, so a second sweep escalates nothing.
        assertThat(sweeper.sweep()).isZero();
    }

    @Test
    void assignmentEnqueuesAReminderNotification() throws Exception {
        assertThat(post("/purchase-requests/PR-8/submit", "requester-1").statusCode())
                .isEqualTo(200);
        // Opening the approver's task enqueued a NOTIFICATION outbox event addressed to them.
        assertThat(notificationCount("PR-8")).isEqualTo(1);
        assertThat(queryString("select payload_json from tql_outbox_event "
                + "where event_type = 'NOTIFICATION' and payload_json like ?",
                "%\"doc\":\"PR-8\"%"))
                .contains("approver-1");
    }

    private static HttpResponse<String> post(String path, String sub) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token(sub))
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

    /** NOTIFICATION outbox events whose envelope payload names the document (a reminder). */
    private static int notificationCount(String docId) throws Exception {
        return Integer.parseInt(queryString("select count(*) from tql_outbox_event "
                + "where event_type = 'NOTIFICATION' and payload_json like ?",
                "%\"doc\":\"" + docId
                        + "\"%"));
    }

    private static int escalateHistoryCount(String docId) throws Exception {
        return Integer.parseInt(queryString("select count(*) from tql_workflow_history "
                + "where doc_id = ? and transition = 'escalate'", docId));
    }

    private static int systemHistoryCount(String docId, String transition) throws Exception {
        return Integer.parseInt(queryString("select count(*) from tql_workflow_history "
                + "where doc_id = ? and transition = ? and actor = 'system'", docId, transition));
    }

    private static int taskCount(String docId, String status) throws Exception {
        return Integer.parseInt(queryString(
                "select count(*) from tql_workflow_task where doc_id = ? and status = ?",
                docId, status));
    }

    private static String openTaskAssignee(String docId) throws Exception {
        return queryString("select assignee from tql_workflow_task "
                + "where doc_id = ? and status = 'OPEN'", docId);
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
                    + "('PR-1','Laptop',1000), ('PR-2','Pen',0), ('PR-3','Desk',500), "
                    + "('PR-4','Chair',700), ('PR-5','Lamp',300), ('PR-6','Phone',900), "
                    + "('PR-7','Mouse',150), ('PR-8','Cable',80)");
            // App-mode: state lives in the status column, initialized to the initial state.
            statement.execute("create table expenses (id varchar(64) primary key, "
                    + "amount numeric(12,2) not null, status varchar(32) not null, "
                    + "note varchar(64))");
            statement.execute(
                    "insert into expenses (id, amount, status) values ('EX-1',200,'draft')");
            // A workflow whose 'submitted' state carries a deadline, for the reassign sweeper test.
            statement.execute("create table escalating_requests (id varchar(64) primary key, "
                    + "last_action varchar(32))");
            statement.execute("insert into escalating_requests (id) values ('ER-1')");
            // A workflow whose 'submitted' deadline auto-fires the approve transition (onBreach.escalate).
            statement.execute("create table auto_requests (id varchar(64) primary key, "
                    + "last_action varchar(32), acted_by varchar(64))");
            statement.execute("insert into auto_requests (id) values ('AU-1')");
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
                http: { basePath: /purchase-requests }
                security: { auth: bearer }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: submitted }
                  - { id: approved, type: terminal }
                  - { id: rejected, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: submitted
                    guard: "document.amount > 0"
                    command: submit.sql
                    assign: { file: approver.sql }
                  - { id: approve, from: submitted, to: approved, command: approve.sql }
                  - { id: reject, from: submitted, to: rejected, command: reject.sql }
                notify:
                  assigned:
                    channel: task-reminders
                    payload:
                      to: assignee
                      doc: document.id
                """);
        Files.writeString(workflowDir.resolve("submit.sql"), "update purchase_requests set "
                + "last_action = 'submit', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approve.sql"), "update purchase_requests set "
                + "last_action = 'approve', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("reject.sql"), "update purchase_requests set "
                + "last_action = 'reject', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approver.sql"),
                "select 'approver-1' as assignee\n");

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

        Files.writeString(workflowDir.resolve("escalating_request.yml"), """
                version: tesseraql/v1
                id: escalating_request
                kind: workflow
                document: { type: escalating_request, table: escalating_requests, key: id }
                http: { basePath: /escalating-requests }
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
                    command: e_submit.sql
                    assign: { file: e_approver.sql }
                  - { id: approve, from: submitted, to: approved, command: e_approve.sql }
                deadlines:
                  - state: submitted
                    within: 0s
                    onBreach: { reassign: dept_head.sql }
                notify:
                  escalated:
                    channel: task-reminders
                    payload:
                      to: assignee
                      doc: docId
                """);
        Files.writeString(workflowDir.resolve("e_submit.sql"),
                "update escalating_requests set last_action = 'submit' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("e_approve.sql"),
                "update escalating_requests set last_action = 'approve' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("e_approver.sql"),
                "select 'approver-1' as assignee\n");
        Files.writeString(workflowDir.resolve("dept_head.sql"),
                "select 'dept-head-1' as assignee\n");

        Files.writeString(workflowDir.resolve("auto_request.yml"),
                """
                        version: tesseraql/v1
                        id: auto_request
                        kind: workflow
                        document: { type: auto_request, table: auto_requests, key: id }
                        http: { basePath: /auto-requests }
                        security: { auth: bearer }
                        initial: draft
                        states:
                          - { id: draft, type: initial }
                          - { id: submitted }
                          - { id: approved, type: terminal }
                        transitions:
                          - { id: submit, from: draft, to: submitted, command: a_submit.sql, assign: { file: a_approver.sql } }
                          - { id: approve, from: submitted, to: approved, command: a_approve.sql }
                        deadlines:
                          - state: submitted
                            within: 0s
                            onBreach: { escalate: approve }
                        """);
        Files.writeString(workflowDir.resolve("a_submit.sql"),
                "update auto_requests set last_action = 'submit' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("a_approve.sql"), "update auto_requests set "
                + "last_action = 'approve', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("a_approver.sql"),
                "select 'approver-1' as assignee\n");
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
