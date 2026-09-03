package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Phase 23 acceptance flow over HTTP: the scaffold-built example gallery app serves a full
 * CRUD round trip — its migration applies at mount, the authenticated list page (carrying the
 * {@code <meta name="csrf-token">} the htmx forms need) and htmx fragment render, a create
 * succeeds over the no-JS path (a plain form post with the hidden {@code _csrf} field, redirecting
 * via {@code Location}), an update succeeds over the htmx path (the {@code X-CSRF-Token} header,
 * redirecting via {@code HX-Redirect}), a missing token is rejected with {@code 403}, a stale
 * lock value answers {@code 409 Conflict} with the conflict dialog (docs/edit-conflict.md), a
 * duplicate name maps to the scaffolded field-errors fragment, and a delete removes the row.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScaffoldedCrudIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static String csrf;

    @BeforeAll
    static void startRuntime() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        SessionStore sessions = runtime.context().lookup(TesseraqlProperties.SESSION_STORE_BEAN,
                SessionStore.class);
        String sid = sessions.create(new Principal("u001", "sato", "Sato", null,
                List.of(), List.of("APP_READ", "APP_WRITE"), List.of(), Map.of()),
                SessionStore.ClientInfo.NONE);
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
        // The single list route renders rows, search box, and sortable headers inline
        // (the tql/view/list pattern re-renders itself over htmx — no fragment route).
        assertThat(list.body()).contains("First item")
                .contains("type=\"search\"")
                .contains("data-sortable")
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
    void theListSortsServerSideThroughItsOwnRoute() throws Exception {
        // Unsorted (the pk default): the name header offers the kit's sort affordance.
        HttpResponse<String> unsorted = get("/items", cookie);
        assertThat(unsorted.statusCode()).isEqualTo(200);
        assertThat(unsorted.body()).contains("First item")
                .contains("data-col=\"name\"")
                .contains("aria-sort=\"none\"");

        // Sorting by a column renders the active aria-sort the kit draws its arrow from, and the
        // server-side ORDER BY (a per-column allowlist) keeps the seeded row in the result.
        HttpResponse<String> sorted = get("/items?sort=name&dir=asc", cookie);
        assertThat(sorted.statusCode()).isEqualTo(200);
        assertThat(sorted.body()).contains("aria-sort=\"ascending\"").contains("First item");

        // The live search narrows through the same route.
        assertThat(get("/items?q=no-such-row", cookie).body()).doesNotContain("First item");

        // Declarative pagination (Phase 41): the scaffold's page block counts and renders the
        // kit pager; the automatic X-Total-Count header rides along.
        HttpResponse<String> paged = get("/items", cookie);
        assertThat(paged.body()).contains("hc-pagination");
        assertThat(paged.headers().firstValue("X-Total-Count")).isPresent();
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
                "unit_price", "1.50",
                "due_date", "2026-07-01",
                "active", "true",
                "note", "Created over the no-JS path"));
        assertThat(created.statusCode()).as(created::body).isEqualTo(303);
        assertThat(created.headers().firstValue("Location")).contains("/items/2");
    }

    @Test
    @Order(4)
    void htmxUpdateRedirectsViaHxRedirectAndOptimisticLockingHolds() throws Exception {
        // The lock is the framework's field now, rendered from the row the page read. Both forms
        // on the page carry it: the edit form from the form pattern, the confirmed delete from
        // the scaffolded fragment, because each posts to a route with its own lock:.
        HttpResponse<String> edit = get("/items/2", cookie);
        assertThat(edit.statusCode()).isEqualTo(200);
        assertThat(edit.body()).contains("value=\"Second item\"")
                .contains("name=\"_lock\" value=\"1\"")
                .contains("id=\"items-edit-form\"")
                .doesNotContain("name=\"version\"");
        // Twice, not once: the form pattern renders one for the edit form, and the scaffolded
        // delete fragment renders its own off v.lock. Each form carries its own route's lock.
        assertThat(edit.body().split("name=\"_lock\"", -1)).hasSize(3);

        // htmx path: HX-Request + the X-CSRF-Token header → 204 + HX-Redirect (no Location swap).
        HttpResponse<String> updated = post("/items/2/update", cookie, csrf, null, Map.of(
                "name", "Second item (edited)",
                "quantity", "3",
                "unit_price", "2.50",
                "due_date", "2026-07-02",
                "active", "false",
                "note", "Edited over the htmx path",
                "_lock", "1"));
        assertThat(updated.statusCode()).as(updated::body).isEqualTo(204);
        assertThat(updated.headers().firstValue("HX-Redirect")).contains("/items/2");
        assertThat(updated.headers().firstValue("Location")).isEmpty();

        // Replaying the stale lock is the conflict, not a silent lost update - and to an htmx
        // caller it arrives as the dialog, retargeted out of the form to the shell's host.
        // HX-Trigger is the edit form's own id, which is what lets the dialog offer an overwrite
        // button associated with that form rather than one pointing at nothing.
        HttpResponse<String> stale = post("/items/2/update", cookie, csrf, null, Map.of(
                "name", "Stale write", "quantity", "9", "active", "true", "_lock", "1"),
                "items-edit-form");
        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        assertThat(stale.body()).contains("data-tql-conflict-dialog")
                .contains("form=\"items-edit-form\"")
                .contains("name=\"_overwrite\"")
                // Reload goes where a successful save would have: the route's own redirect.
                .contains("href=\"/items/2\"");
        assertThat(stale.headers().firstValue("HX-Retarget"))
                .contains("[data-tql-conflict-host]");

        // The waiver applies the write over whatever the row holds now, once.
        HttpResponse<String> overwritten = post("/items/2/update", cookie, csrf, null, Map.of(
                "name", "Overwritten", "quantity", "9", "active", "true",
                "_lock", "1", "_overwrite", "1"));
        assertThat(overwritten.statusCode()).as(overwritten::body).isEqualTo(204);

        // A duplicate name is now caught pre-write by the scaffolded shared rule
        // (docs/validation-rule-sets.md): a friendly 422 field error instead of the
        // post-write constraint 409 — the constraint catalog still backs the race window.
        HttpResponse<String> duplicate = post("/items/create", cookie, csrf, null, Map.of(
                "name", "First item", "quantity", "1", "active", "true"));
        assertThat(duplicate.statusCode()).as(duplicate::body).isEqualTo(422);
        assertThat(duplicate.body()).contains("data-hc-field-errors")
                .contains("data-field=\"name\"");

        // The delete posts the lock the confirmed-delete fragment rendered, not a literal: the
        // fragment reads v.lock off the view model, and an empty one would 400 here.
        String deleteForm = get("/items/2", cookie).body();
        deleteForm = deleteForm.substring(deleteForm.indexOf("id=\"items-delete-form\""));
        assertThat(lockValue(deleteForm)).isEqualTo("3");
        HttpResponse<String> deleted = post("/items/2/delete", cookie, csrf, null,
                Map.of("_lock", lockValue(deleteForm)));
        assertThat(deleted.statusCode()).as(deleted::body).isEqualTo(204);
        assertThat(deleted.headers().firstValue("HX-Redirect")).contains("/items");
        // Named for what the row actually holds by now: asserting the name it carried three
        // writes ago would pass on a delete that deleted nothing.
        assertThat(get("/items", cookie).body()).doesNotContain("Overwritten");
        // The detail page renders its not-found state rather than 404ing, and the edit form -
        // the one that carried a lock value - is gone with the row. The footer slot still
        // mounts, which is how the scaffolded page has always behaved for a missing row.
        String gone = get("/items/2", cookie).body();
        assertThat(gone).contains("hc-empty__title").doesNotContain("id=\"items-edit-form\"");
        assertThat(lockValue(gone)).isEmpty();
    }

    /** The first rendered {@code _lock} value in the given markup. */
    private static String lockValue(String markup) {
        java.util.regex.Matcher field = java.util.regex.Pattern
                .compile("name=\"_lock\"[^>]*value=\"([^\"]*)\"")
                .matcher(markup);
        assertThat(field.find()).as("no _lock field in the markup").isTrue();
        return field.group(1);
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
        return post(path, sessionCookie, csrfHeader, csrfField, fields, null);
    }

    /** The same, with the {@code HX-Trigger} header a real htmx form submit carries. */
    private static HttpResponse<String> post(String path, String sessionCookie, String csrfHeader,
            String csrfField, Map<String, String> fields, String trigger) throws Exception {
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
        if (trigger != null) {
            request.header("HX-Trigger", trigger);
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

}
