package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * The procurement gallery app's requisition approval, over HTTP on the real app
 * (docs/procurement-demo.md): a submitted requisition opens a task for the requesting
 * department's manager, and only that manager may settle it.
 *
 * <p>The gallery's three assign resolvers ({@code approver.sql}, {@code rfq-owner.sql},
 * {@code order-owner.sql}) look the assignee up by {@code /* key *}{@code /}, the natural
 * document-keyed shape — and until docs/audit-low-leads.md slice 2a the engine never told the
 * resolver its document, so none of the promised tasks existed and any MANAGER could approve any
 * requisition. The declarative suites could not see it: a {@code transition:} case does not model
 * task opening (docs/testing.md), and the suite exercises {@code approver.sql} as a bare
 * {@code sql:} case with an explicit key. This is the HTTP-level proof.
 */
@Testcontainers
class ProcurementRequisitionTaskIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final tools.jackson.databind.ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers
            .constrained();
    /** The gallery app's dev default (config: {@code ${JWT_SECRET:...}}). */
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = copyGalleryApp();
        // The runtime applies the app's own db/migration at boot: departments, requisitions,
        // and the seeded REQ-1002 (sales, office, 96000 — the manager lane).
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
    void aSubmittedRequisitionOpensATaskForTheDepartmentManagerWhoAloneMaySettleIt()
            throws Exception {
        // sato (the requester) submits REQ-1002; the sales department's manager is kishi.
        assertThat(fire("REQ-1002", "submit", "sato", "REQUESTER").statusCode()).isEqualTo(200);
        assertThat(openTaskAssignee("REQ-1002")).isEqualTo("kishi");

        // Another manager holds the route policy but not the task: the framework-enforced
        // task-authority gate refuses them, and the document stays where it was.
        HttpResponse<String> intruder = fire("REQ-1002", "submit_decision", "intruder", "MANAGER");
        assertThat(intruder.statusCode()).isEqualTo(403);
        assertThat(intruder.body()).contains("TQL-WORKFLOW-3203");
        assertThat(instanceState("REQ-1002")).isEqualTo("submitted");

        // The assignee settles it through the one-action dispatch; the manager lane approves.
        HttpResponse<String> settled = fire("REQ-1002", "submit_decision", "kishi", "MANAGER");
        assertThat(settled.statusCode()).isEqualTo(200);
        assertThat(settled.body()).contains("\"transition\":\"approve\"");
        assertThat(instanceState("REQ-1002")).isEqualTo("approved");
        assertThat(openTaskAssignee("REQ-1002")).isNull();
    }

    private static HttpResponse<String> fire(String key, String transition, String actor,
            String role) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/api/requisitions/" + key + "/" + transition))
                .header("Authorization", "Bearer " + token(actor, role))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String openTaskAssignee(String docId) throws Exception {
        return queryString("select assignee from tql_workflow_task "
                + "where doc_id = ? and status = 'OPEN'", docId);
    }

    private static String instanceState(String docId) throws Exception {
        return queryString("select current_state from tql_workflow_instance "
                + "where doc_type = 'requisition' and doc_id = ?", docId);
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

    private static String token(String sub, String role) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", sub, "roles", List.of(role)))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path copyGalleryApp() throws IOException {
        Path source = Path.of("../examples/procurement-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-procurement-task-it");
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
