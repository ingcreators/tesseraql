package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
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
 * Milestone M16 on the REAL purchase-request gallery app (roadmap Phase 52 slice 2): an
 * approver sets an absence with a delegate; a request submitted during the window lands in
 * the delegate's inbox marked "for" the approver; the delegate approves it as themselves
 * and the trail shows who acted for whom; after the window ends new requests reach the
 * approver again — no permission ever borrowed, no chain ever followed.
 */
@Testcontainers
class PurchaseRequestDelegationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    /** The gallery app's dev default (config: {@code ${JWT_SECRET:...}}). */
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String approverCookie;
    static String approverCsrf;

    @BeforeAll
    static void start() throws Exception {
        appHome = copyGalleryApp();
        // The runtime applies the app's own db/migration at boot - no manual step.
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("approver-1", "approver-1", "Approver",
                null, List.of(), List.of("APPROVER"), List.of(), Map.of()));
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
    void milestoneM16TheAbsenceLoopOnTheGalleryApp() throws Exception {
        // The approver sets their out-of-office on the account page.
        assertThat(postAccountForm("/_tesseraql/account/delegation",
                "delegate=deputy-1&startsAt=" + Instant.now().minusSeconds(60)
                        + "&endsAt=" + Instant.now().plusSeconds(3600))
                .statusCode()).isEqualTo(303);

        // A request submitted during the window lands with the delegate, marked for the
        // approver; the absent approver cannot act on it, the delegate approves as
        // themselves, and the completed row is the who-for-whom trail.
        createRequest("PR-M16-1", 250);
        assertThat(fire("PR-M16-1", "submit", "requester-1").statusCode()).isEqualTo(200);
        Map<String, String> task = taskRow("PR-M16-1");
        assertThat(task.get("assignee")).isEqualTo("deputy-1");
        assertThat(task.get("delegated_from")).isEqualTo("approver-1");
        assertThat(fire("PR-M16-1", "approve", "approver-1").statusCode()).isEqualTo(403);
        assertThat(fire("PR-M16-1", "approve", "deputy-1").statusCode()).isEqualTo(200);
        Map<String, String> done = taskRow("PR-M16-1");
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("completed_by")).isEqualTo("deputy-1");
        assertThat(done.get("delegated_from")).isEqualTo("approver-1");

        // After the window ends, new requests reach the approver again.
        assertThat(postAccountForm("/_tesseraql/account/delegation/clear", "")
                .statusCode()).isEqualTo(303);
        createRequest("PR-M16-2", 99);
        assertThat(fire("PR-M16-2", "submit", "requester-1").statusCode()).isEqualTo(200);
        Map<String, String> after = taskRow("PR-M16-2");
        assertThat(after.get("assignee")).isEqualTo("approver-1");
        assertThat(after.get("delegated_from")).isNull();
    }

    private static void createRequest(String id, int amount) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("insert into purchase_requests (id, title, amount, requested_by) "
                    + "values ('" + id + "', 'M16', " + amount + ", 'requester-1')");
        }
    }

    private static HttpResponse<String> postAccountForm(String path, String form)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", approverCookie)
                .header("X-CSRF-Token", approverCsrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> fire(String key, String transition, String actor)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/api/purchase-requests/" + key + "/" + transition))
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
            Map<String, String> row = new java.util.HashMap<>();
            row.put("assignee", rs.getString(1));
            row.put("delegated_from", rs.getString(2));
            row.put("status", rs.getString(3));
            row.put("completed_by", rs.getString(4));
            return row;
        }
    }

    private static String token(String sub) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", sub, "roles", List.of("APPROVER"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path copyGalleryApp() throws IOException {
        Path source = Path.of("../examples/purchase-request-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-pr-m16-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> {
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
            });
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
        return target;
    }
}
