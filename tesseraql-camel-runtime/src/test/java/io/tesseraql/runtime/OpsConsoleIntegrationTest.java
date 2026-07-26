package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Paths;
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
 * Integration test for the bundled operations console app (design ch. 26.11, 32, 47): the
 * yaml/template app shipped in tesseraql-ops-ui mounts automatically and renders the ops.* service
 * providers under a strict content security policy; callers without a bearer principal are denied.
 */
@Testcontainers
class OpsConsoleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    // The ops console is now browser-session auth; an authenticated GET carries this admin cookie.
    static String adminCookie;
    static String adminCsrf;
    // A scope-granted operator (ops.app.*): sees every app's rows and may act on them.
    static String scopedCookie;
    static String scopedCsrf;
    // Sees rows (view policy + scope) but holds no run policy.
    static String viewerCookie;
    static String viewerCsrf;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext().getRegistry()
                .lookupByNameAndType(io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String adminSid = sessions.create(
                new io.tesseraql.security.Principal("ops-user", "ops-user", "Ops User", null,
                        List.of(), List.of("ADMIN"), List.of(), Map.of()));
        adminCookie = sessions.cookieName() + "=" + adminSid;
        adminCsrf = sessions.session(adminSid).csrfToken();
        String scopedSid = sessions.create(
                new io.tesseraql.security.Principal("ops-admin", "ops-admin", "Ops Admin", null,
                        List.of(), List.of("ADMIN"), List.of("ops.app.*"), Map.of()));
        scopedCookie = sessions.cookieName() + "=" + scopedSid;
        scopedCsrf = sessions.session(scopedSid).csrfToken();
        String viewerSid = sessions.create(
                new io.tesseraql.security.Principal("ops-viewer", "ops-viewer", "Ops Viewer",
                        null, List.of(), List.of("BATCH_VIEWER"), List.of("ops.app.*"), Map.of()));
        viewerCookie = sessions.cookieName() + "=" + viewerSid;
        viewerCsrf = sessions.session(viewerSid).csrfToken();
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
    void rendersHtmlDashboardForAuthorizedCaller() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        assertThat(response.body()).contains("TesseraQL Operations Console");
        assertThat(response.body()).contains("Execution lanes");
        // Long dashboard gets an in-page "jump to section" nav (sidebar IA).
        assertThat(response.body()).contains("Jump to").contains("href=\"#batch\"");
    }

    @Test
    void consolePagesCarryTheOpsSidebarNav() throws Exception {
        // The console mounts its own section nav in the shell sidebar (sidebar IA), like Studio: the
        // sub-views are reachable from anywhere, with the other system apps linked below. Renders on
        // the overview and a deep sub-page alike.
        for (String path : new String[]{"/_tesseraql/ops/console",
                "/_tesseraql/ops/console/traces"}) {
            String body = get(path, true).body();
            assertThat(body).contains("hc-shell__sidebar").contains("data-hc-nav-current")
                    .contains(">Overview<").contains(">Jobs<").contains(">Traces<")
                    .contains(">Transfers<").contains(">Outbox<").contains(">Audit<")
                    // the other system apps stay reachable
                    .contains(">Studio<").contains(">IAM Admin<")
                    // icons via the self-hosted sprite
                    .contains("/assets/_tesseraql/icons.svg#waypoints");
        }
    }

    @Test
    void overviewShowsTheHealthPanelAndTheVersion() throws Exception {
        // The health() roll-up, its per-datasource probe map, and the deployed version
        // join the operator's first screen (docs/ops-console-coverage.md).
        String body = get("/_tesseraql/ops/console", true).body();

        assertThat(body).contains("id=\"health\"")
                .contains("main: reachable")
                .contains(io.tesseraql.core.TesseraqlVersion.current());
    }

    @Test
    void auditPageNamesTheFlagWhenTheStoreIsOff() throws Exception {
        // This runtime does not enable tesseraql.audit.routes.enabled: the page must say
        // so instead of rendering an empty table that pretends nothing happened
        // (docs/ops-console-coverage.md).
        HttpResponse<String> response = get("/_tesseraql/ops/console/audit", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Route audit is not enabled")
                .contains("tesseraql.audit.routes.enabled");
    }

    @Test
    void overviewUsesSelfHostedHtmxForPolling() throws Exception {
        HttpResponse<String> page = get("/_tesseraql/ops/console", true);
        assertThat(page.body())
                .contains("/assets/vendor/htmx.org/dist/htmx.min.js")
                .contains("hx-trigger=\"every 15s\"");

        // The vendored libraries serve from classpath WebJars at version-less URLs - no external
        // CDN, and upgrades are a pom version bump with templates unchanged.
        HttpResponse<String> htmx = get("/assets/vendor/htmx.org/dist/htmx.min.js", false);
        assertThat(htmx.statusCode()).isEqualTo(200);
        assertThat(htmx.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/javascript"));
        assertThat(htmx.body()).contains("htmx");

        assertThat(page.body())
                .contains("/assets/vendor/hypermedia-components__core/dist/hc.min.css");
        HttpResponse<String> hc = get(
                "/assets/vendor/hypermedia-components__core/dist/hc.min.css", false);
        assertThat(hc.statusCode()).isEqualTo(200);
        assertThat(hc.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/css"));
        assertThat(hc.body()).contains("hc-card");
    }

    @Test
    void rendersFileTransfersPage() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console/transfers", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        // No ops.app.* grant on this caller: deny-by-default leaves the table empty.
        assertThat(response.body()).contains("File transfers")
                .contains("No file transfers recorded");
    }

    @Test
    void rendersTraceTreePage() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console/traces", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        // No ops.app.* grant on this caller: deny-by-default leaves the trace table empty.
        assertThat(response.body()).contains("Traces").contains("No traces retained");
        // Refreshes like every other console page - traces used to be the one static view.
        assertThat(response.body()).contains("hx-trigger=\"every 15s\"");
    }

    @Test
    void rendersNotFoundPageForUnknownExecution() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console/executions/missing", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Execution not found.");
    }

    @Test
    void requiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/ops/console", false).statusCode()).isEqualTo(401);
        assertThat(get("/_tesseraql/ops/console/traces", false).statusCode()).isEqualTo(401);
    }

    @Test
    void redeliverButtonRendersOnlyOnDeadRows() throws Exception {
        String deadId = seedDeadEvent();
        String pendingId = outboxStore().insert(outboxEvent());

        String body = getWith("/_tesseraql/ops/console/outbox", scopedCookie).body();

        // The row-level form carries the event id; FAILED/PENDING rows stay button-free
        // (docs/ops-console-actions.md: not-yet-dead events are the dispatcher's to retry).
        assertThat(body).contains("action=\"/_tesseraql/ops/console/outbox/redeliver\"")
                .contains("name=\"id\" value=\"" + deadId + "\"")
                .doesNotContain("name=\"id\" value=\"" + pendingId + "\"");
    }

    @Test
    void redeliverRequeuesADeadEvent() throws Exception {
        String deadId = seedDeadEvent();

        HttpResponse<String> response = postForm("/_tesseraql/ops/console/outbox/redeliver",
                "id=" + deadId, scopedCookie, scopedCsrf);

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("location"))
                .hasValueSatisfying(value -> assertThat(value).contains("redelivered=1"));
        assertThat(outboxStore().find(deadId).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void redeliverOutOfScopeReadsAsUnknown() throws Exception {
        String deadId = seedDeadEvent();

        // The plain admin session holds no ops.app.* grant: deny-by-default hides the
        // event, and out-of-scope answers exactly like unknown (the JSON API's stance).
        HttpResponse<String> response = postForm("/_tesseraql/ops/console/outbox/redeliver",
                "id=" + deadId, adminCookie, adminCsrf);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(outboxStore().find(deadId).orElseThrow().status()).isEqualTo("DEAD");
    }

    @Test
    void redeliverRequiresTheRunPolicy() throws Exception {
        String deadId = seedDeadEvent();

        // BATCH_VIEWER satisfies ops.batch.view but not ops.batch.run.
        HttpResponse<String> response = postForm("/_tesseraql/ops/console/outbox/redeliver",
                "id=" + deadId, viewerCookie, viewerCsrf);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(outboxStore().find(deadId).orElseThrow().status()).isEqualTo("DEAD");
    }

    @Test
    void redeliverRequiresACsrfToken() throws Exception {
        String deadId = seedDeadEvent();

        HttpResponse<String> response = postForm("/_tesseraql/ops/console/outbox/redeliver",
                "id=" + deadId, scopedCookie, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(outboxStore().find(deadId).orElseThrow().status()).isEqualTo("DEAD");
    }

    @Test
    void jobsPageListsTheScopedCatalogWithRunButtons() throws Exception {
        String body = getWith("/_tesseraql/ops/console/jobs", scopedCookie).body();

        assertThat(body).contains("Batch jobs").contains("user.dailyMaintenance")
                .contains("cron 0 0 2 * * ?")
                .contains("action=\"/_tesseraql/ops/console/jobs/run\"");
        // Deny-by-default: a caller without ops.app.* grants sees an empty catalog.
        assertThat(getWith("/_tesseraql/ops/console/jobs", adminCookie).body())
                .contains("No jobs declared.");
    }

    @Test
    void runStartsAJobRecordsTheActorAndRedirectsToItsExecution() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/ops/console/jobs/run",
                "id=user.dailyMaintenance", scopedCookie, scopedCsrf);

        assertThat(response.statusCode()).isEqualTo(303);
        String location = response.headers().firstValue("location").orElseThrow();
        assertThat(location).contains("/_tesseraql/ops/console/executions/")
                .contains("started=1");

        String detail = getWith(location, scopedCookie).body();
        assertThat(detail).contains("Job started.").contains("Triggered by")
                .contains("ops-admin").contains(">manual<");
    }

    @Test
    void runRequiresTheRunPolicyAndScope() throws Exception {
        // BATCH_VIEWER satisfies ops.batch.view but not ops.batch.run.
        assertThat(postForm("/_tesseraql/ops/console/jobs/run", "id=user.dailyMaintenance",
                viewerCookie, viewerCsrf).statusCode()).isEqualTo(403);
        // No ops.app.* grant: the job reads exactly like an unknown one.
        assertThat(postForm("/_tesseraql/ops/console/jobs/run", "id=user.dailyMaintenance",
                adminCookie, adminCsrf).statusCode()).isEqualTo(404);
    }

    private static io.tesseraql.operations.outbox.JdbcOutboxStore outboxStore() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                io.tesseraql.camel.TesseraqlProperties.OUTBOX_STORE_BEAN,
                io.tesseraql.operations.outbox.JdbcOutboxStore.class);
    }

    private static io.tesseraql.core.outbox.OutboxEvent outboxEvent() {
        return new io.tesseraql.core.outbox.OutboxEvent(null, "user", "sato",
                "USER_PROVISIONED", "{}", "PENDING", 0, null, java.time.Instant.now(), null,
                "user-admin");
    }

    private static String seedDeadEvent() {
        io.tesseraql.operations.outbox.JdbcOutboxStore outbox = outboxStore();
        String id = outbox.insert(outboxEvent());
        outbox.markDead(id, "delivery kept failing");
        return id;
    }

    private static HttpResponse<String> getWith(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", cookie);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A browser-session form POST; {@code csrf} null leaves the token header off. */
    private static HttpResponse<String> postForm(String path, String form, String cookie,
            String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (csrf != null) {
            request.header("X-CSRF-Token", csrf);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (auth) {
            request.header("Authorization", "Bearer " + token()).header("Cookie", adminCookie);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "ops-user", "roles", List.of("ADMIN"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-ops-console-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
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

    private static void copy(Path source, Path target, Path path) {
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
