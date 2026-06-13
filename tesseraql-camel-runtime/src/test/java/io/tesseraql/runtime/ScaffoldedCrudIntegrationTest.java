package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Phase 23 acceptance flow over HTTP: the scaffold-built example gallery app serves a full
 * CRUD round trip — its migration applies at mount, the authenticated list page (carrying the
 * {@code <meta name="csrf-token">} the htmx forms need) and htmx fragment render, a create
 * succeeds over the no-JS path (a plain form post with the hidden {@code _csrf} field, redirecting
 * via {@code Location}), an update succeeds over the htmx path (the {@code X-CSRF-Token} header,
 * redirecting via {@code HX-Redirect}), a missing token is rejected with {@code 403}, a stale
 * version answers {@code 409 Conflict} (Phase 18 optimistic locking), a duplicate name maps to the
 * scaffolded field-errors fragment, and a delete removes the row.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScaffoldedCrudIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static String csrf;

    @BeforeAll
    static void startRuntime() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("u001", "sato", "Sato", null,
                List.of(), List.of("APP_READ", "APP_WRITE"), List.of(), Map.of()));
        cookie = sessions.cookieName() + "=" + sid;
        csrf = sessions.csrfToken(sid);
    }

    @AfterAll
    static void stopRuntime() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    @Order(1)
    void homeIsPublicButItemPagesRequireASessionAndCarryTheCsrfToken() throws Exception {
        HttpResponse<String> home = get("/", null);
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Welcome to scaffold-demo");

        // The list and create-form pages are browser-authed: anonymous → 401.
        assertThat(get("/items", null).statusCode()).isEqualTo(401);
        assertThat(get("/items/new", null).statusCode()).isEqualTo(401);

        HttpResponse<String> list = get("/items", cookie);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("hx-get=\"/items/fragments/table\"")
                // The shell publishes the session CSRF token for installCsrfHeader.
                .contains("<meta name=\"csrf-token\" content=\"" + csrf + "\">");

        HttpResponse<String> form = get("/items/new", cookie);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("class=\"hc-datepicker\"")
                // The hidden field for the no-JS path is rendered with the token.
                .contains("name=\"_csrf\" value=\"" + csrf + "\"");
    }

    @Test
    @Order(2)
    void fragmentRequiresASessionAndRendersTheSeededRow() throws Exception {
        assertThat(get("/items/fragments/table", null).statusCode()).isEqualTo(401);

        HttpResponse<String> fragment = get("/items/fragments/table", cookie);
        assertThat(fragment.statusCode()).isEqualTo(200);
        assertThat(fragment.body()).contains("First item");
    }

    @Test
    @Order(3)
    void mutationsRequireCsrfOnBothTheNoJsAndHtmxPaths() throws Exception {
        // No token at all → rejected.
        HttpResponse<String> noToken = post("/items/create", cookie, null, null, Map.of(
                "name", "No CSRF", "quantity", "1", "active", "true"));
        assertThat(noToken.statusCode()).isEqualTo(403);

        // No-JS path: a plain form post carrying the hidden _csrf field redirects (303 + Location).
        HttpResponse<String> created = post("/items/create", cookie, null, csrf, Map.of(
                "name", "Second item",
                "quantity", "2",
                "unitPrice", "1.50",
                "dueDate", "2026-07-01",
                "active", "true",
                "note", "Created over the no-JS path"));
        assertThat(created.statusCode()).as(created::body).isEqualTo(303);
        assertThat(created.headers().firstValue("Location")).contains("/items/2");
    }

    @Test
    @Order(4)
    void htmxUpdateRedirectsViaHxRedirectAndOptimisticLockingHolds() throws Exception {
        // The edit page renders the row with its version for the optimistic-locking flow.
        HttpResponse<String> edit = get("/items/2", cookie);
        assertThat(edit.statusCode()).isEqualTo(200);
        assertThat(edit.body()).contains("value=\"Second item\"")
                .contains("name=\"version\" value=\"1\"");

        // htmx path: HX-Request + the X-CSRF-Token header → 204 + HX-Redirect (no Location swap).
        HttpResponse<String> updated = post("/items/2/update", cookie, csrf, null, Map.of(
                "name", "Second item (edited)",
                "quantity", "3",
                "unitPrice", "2.50",
                "dueDate", "2026-07-02",
                "active", "false",
                "note", "Edited over the htmx path",
                "version", "1"));
        assertThat(updated.statusCode()).as(updated::body).isEqualTo(204);
        assertThat(updated.headers().firstValue("HX-Redirect")).contains("/items/2");
        assertThat(updated.headers().firstValue("Location")).isEmpty();

        // Replaying the stale version is the Phase 18 conflict, not a silent lost update.
        HttpResponse<String> stale = post("/items/2/update", cookie, csrf, null, Map.of(
                "name", "Stale write", "quantity", "9", "active", "true", "version", "1"));
        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        assertThat(stale.body()).contains("TQL-SQL-4092");

        // A duplicate name surfaces as the scaffolded field-level constraint mapping; an htmx
        // caller gets the kit's field-errors fragment (the X-CSRF carrier marks the request htmx).
        HttpResponse<String> duplicate = post("/items/create", cookie, csrf, null, Map.of(
                "name", "First item", "quantity", "1", "active", "true"));
        assertThat(duplicate.statusCode()).as(duplicate::body).isEqualTo(409);
        assertThat(duplicate.body()).contains("data-hc-field-errors")
                .contains("data-field=\"name\"");

        // Delete with the bumped version, then the fragment no longer lists the row.
        HttpResponse<String> deleted = post("/items/2/delete", cookie, csrf, null,
                Map.of("version", "2"));
        assertThat(deleted.statusCode()).as(deleted::body).isEqualTo(204);
        assertThat(deleted.headers().firstValue("HX-Redirect")).contains("/items");
        assertThat(get("/items/fragments/table", cookie).body())
                .doesNotContain("Second item");
    }

    private static HttpResponse<String> get(String path, String sessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path));
        if (sessionCookie != null) {
            request.header("Cookie", sessionCookie);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Posts a form. {@code csrfHeader} sets the htmx-path {@code X-CSRF-Token} header and marks the
     * request as htmx ({@code HX-Request}); {@code csrfField} adds the no-JS hidden {@code _csrf}
     * field to the body. Pass one or the other (or neither, to prove rejection).
     */
    private static HttpResponse<String> post(String path, String sessionCookie, String csrfHeader,
            String csrfField, Map<String, String> fields) throws Exception {
        Map<String, String> body = new java.util.LinkedHashMap<>(fields);
        if (csrfField != null) {
            body.put("_csrf", csrfField);
        }
        String encoded = body.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofString(encoded));
        if (csrfHeader != null) {
            request.header("X-CSRF-Token", csrfHeader).header("HX-Request", "true");
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A working copy of the gallery app pointed at the test container. */
    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "scaffold-demo-app").toAbsolutePath()
                .normalize();
        Path target = Files.createTempDirectory("tesseraql-scaffold-it");
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
            throw new java.io.UncheckedIOException(ex);
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
