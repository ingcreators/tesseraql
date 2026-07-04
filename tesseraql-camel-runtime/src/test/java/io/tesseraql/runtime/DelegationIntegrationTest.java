package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
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
 * Absence delegation end to end (roadmap Phase 52 slice 1): a standing rule redirects
 * assignment at assignment time, the task records who it was meant for, the delegate acts
 * as themselves while the absent approver holds nothing, resolution is one hop even when
 * the delegate is absent too, and the account card validates its inputs.
 */
@Testcontainers
class DelegationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String approverCookie;
    static String approverCsrf;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("approver-1", "approver-1", "Approver",
                null, List.of(), List.of(), List.of(), Map.of()));
        approverCookie = sessions.cookieName() + "=" + sid;
        approverCsrf = sessions.session(sid).csrfToken();
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
    void anActiveWindowRedirectsAssignmentAndTheTrailShowsWhoActedForWhom() throws Exception {
        // The approver sets their own out-of-office through the account card (raw subject:
        // this app has no identity realm, and the page says so).
        assertThat(saveDelegation("deputy-1", Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(3600)).statusCode()).isEqualTo(303);

        assertThat(transition("PR-1", "submit", "requester-1").statusCode()).isEqualTo(200);
        Map<String, String> task = taskRow("PR-1");
        assertThat(task.get("assignee")).isEqualTo("deputy-1");
        assertThat(task.get("delegated_from")).isEqualTo("approver-1");

        // The absent approver holds nothing - no ghost authority survives the redirect...
        assertThat(transition("PR-1", "approve", "approver-1").statusCode()).isEqualTo(403);
        // ...while the delegate acts as themselves and the completed row carries all three
        // facts: meant for, received by, acted by.
        assertThat(transition("PR-1", "approve", "deputy-1").statusCode()).isEqualTo(200);
        Map<String, String> done = taskRow("PR-1");
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("completed_by")).isEqualTo("deputy-1");
        assertThat(done.get("delegated_from")).isEqualTo("approver-1");
        clearDelegation();
    }

    @Test
    void outsideAnyWindowAssignmentIsUntouched() throws Exception {
        clearDelegation();
        assertThat(transition("PR-2", "submit", "requester-1").statusCode()).isEqualTo(200);
        Map<String, String> task = taskRow("PR-2");
        assertThat(task.get("assignee")).isEqualTo("approver-1");
        assertThat(task.get("delegated_from")).isNull();
    }

    /** Handing a task to an absent target forwards ONE hop - never a chain. */
    @Test
    void theManualHandOverForwardsExactlyOneHop() throws Exception {
        clearDelegation();
        assertThat(transition("PR-3", "submit", "requester-1").statusCode()).isEqualTo(200);
        assertThat(taskRow("PR-3").get("assignee")).isEqualTo("approver-1");

        // colleague-1 is absent (covered by deputy-1); deputy-1 is ALSO absent (covered by
        // approver-1). A hand-over to colleague-1 must stop at deputy-1: one hop, no chain,
        // no loop back to the delegator.
        putRule("colleague-1", "deputy-1");
        putRule("deputy-1", "approver-1");
        HttpResponse<String> delegated = bearerPost(
                "/purchase-requests/PR-3/delegate/colleague-1", "approver-1");
        assertThat(delegated.statusCode()).isEqualTo(200);
        Map<String, String> task = taskRow("PR-3");
        assertThat(task.get("assignee")).isEqualTo("deputy-1");
        assertThat(task.get("delegated_from")).isEqualTo("colleague-1");
        clearRule("colleague-1");
        clearRule("deputy-1");
    }

    @Test
    void theCardRefusesSelfDelegationAndDeadWindows() throws Exception {
        assertThat(saveDelegation("approver-1", Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(3600)).statusCode()).isEqualTo(400);
        assertThat(saveDelegation("deputy-1", Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(60)).statusCode()).isEqualTo(400);
        assertThat(saveDelegation("deputy-1", Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600)).statusCode()).isEqualTo(400);
    }

    private static void putRule(String subject, String delegate) {
        runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.DELEGATION_STORE_BEAN,
                io.tesseraql.core.workflow.DelegationStore.class)
                .put(null, subject, delegate, Instant.now().minusSeconds(60),
                        Instant.now().plusSeconds(3600));
    }

    private static void clearRule(String subject) {
        runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.DELEGATION_STORE_BEAN,
                io.tesseraql.core.workflow.DelegationStore.class).clear(null, subject);
    }

    private static void clearDelegation() throws Exception {
        postForm("/_tesseraql/account/delegation/clear", "");
    }

    private static HttpResponse<String> saveDelegation(String delegate, Instant from,
            Instant until) throws Exception {
        return postForm("/_tesseraql/account/delegation",
                "delegate=" + delegate + "&startsAt=" + from + "&endsAt=" + until);
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", approverCookie)
                .header("X-CSRF-Token", approverCsrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> transition(String key, String id, String actor)
            throws Exception {
        return bearerPost("/purchase-requests/" + key + "/" + id, actor);
    }

    private static HttpResponse<String> bearerPost(String path, String actor)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token(actor))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> taskRow(String docId) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select assignee, delegated_from, status, completed_by "
                                + "from tql_workflow_task where doc_id = '" + docId
                                + "' order by created_at desc limit 1")) {
            assertThat(rs.next()).isTrue();
            return new java.util.HashMap<>(Map.of()) {
                {
                    put("assignee", rs.getString(1));
                    put("delegated_from", rs.getString(2));
                    put("status", rs.getString(3));
                    put("completed_by", rs.getString(4));
                }
            };
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
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table purchase_requests (id varchar(64) primary key, "
                    + "amount numeric(12,2) not null, last_action varchar(32), "
                    + "acted_by varchar(64))");
            statement.execute("insert into purchase_requests (id, amount) values "
                    + "('PR-1', 100), ('PR-2', 100), ('PR-3', 100)");
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-delegation-it");
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
                transitions:
                  - id: submit
                    from: draft
                    to: submitted
                    command: submit.sql
                    assign: { file: approver.sql }
                  - { id: approve, from: submitted, to: approved, command: approve.sql }
                """);
        Files.writeString(workflowDir.resolve("submit.sql"), "update purchase_requests set "
                + "last_action = 'submit', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approve.sql"), "update purchase_requests set "
                + "last_action = 'approve', acted_by = /* audit.user */ 'x' where id = /* key */ 'x'\n");
        Files.writeString(workflowDir.resolve("approver.sql"),
                "select 'approver-1' as assignee\n");
        return home;
    }
}
