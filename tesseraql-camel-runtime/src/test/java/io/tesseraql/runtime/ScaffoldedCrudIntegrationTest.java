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
 * CRUD round trip — its migration applies at mount, the list page and htmx fragment render, a
 * browser form post creates a row (post/redirect/get with the generated key), an edit updates it,
 * a stale version answers {@code 409 Conflict} (Phase 18 optimistic locking), a duplicate name
 * maps to the scaffolded constraint error, and a confirmed delete removes it.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScaffoldedCrudIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;

    @BeforeAll
    static void startRuntime() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("u001", "sato", "Sato", null,
                List.of(), List.of("APP_READ", "APP_WRITE"), List.of(), Map.of()));
        cookie = sessions.cookieName() + "=" + sid;
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
    void homeAndListPagesServeWithoutAuthentication() throws Exception {
        HttpResponse<String> home = get("/", null);
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Welcome to scaffold-demo");

        HttpResponse<String> list = get("/items", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("hx-get=\"/items/fragments/table\"");

        HttpResponse<String> form = get("/items/new", null);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("class=\"hc-datepicker\"");
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
    void crudRoundTripWithOptimisticLockingAndConstraintMapping() throws Exception {
        // Create: a plain browser form post redirects to the new record (generated key 2).
        HttpResponse<String> created = postForm("/items/create", cookie, Map.of(
                "name", "Second item",
                "quantity", "2",
                "unitPrice", "1.50",
                "dueDate", "2026-07-01",
                "active", "true",
                "note", "Created by the integration test"));
        assertThat(created.statusCode()).as(created::body).isEqualTo(303);
        assertThat(created.headers().firstValue("Location")).contains("/items/2");

        // The edit page renders the row with its version for the optimistic-locking flow.
        HttpResponse<String> edit = get("/items/2", cookie);
        assertThat(edit.statusCode()).isEqualTo(200);
        assertThat(edit.body()).contains("value=\"Second item\"")
                .contains("name=\"version\" value=\"1\"");

        // Update with the current version succeeds and bumps it.
        HttpResponse<String> updated = postForm("/items/2/update", cookie, Map.of(
                "name", "Second item (edited)",
                "quantity", "3",
                "unitPrice", "2.50",
                "dueDate", "2026-07-02",
                "active", "false",
                "note", "Edited by the integration test",
                "version", "1"));
        assertThat(updated.statusCode()).as(updated::body).isEqualTo(303);
        assertThat(updated.headers().firstValue("Location")).contains("/items/2");

        // Replaying the stale version is the Phase 18 conflict, not a silent lost update.
        HttpResponse<String> stale = postForm("/items/2/update", cookie, Map.of(
                "name", "Stale write",
                "quantity", "9",
                "unitPrice", "9.99",
                "dueDate", "2026-07-03",
                "active", "true",
                "note", "Must not win",
                "version", "1"));
        assertThat(stale.statusCode()).as(stale::body).isEqualTo(409);
        assertThat(stale.body()).contains("TQL-SQL-4092");

        // A duplicate name surfaces as the scaffolded field-level constraint mapping.
        HttpResponse<String> duplicate = postForm("/items/create", cookie, Map.of(
                "name", "First item",
                "quantity", "1",
                "unitPrice", "1.00",
                "dueDate", "2026-07-04",
                "active", "true",
                "note", "Duplicate"));
        assertThat(duplicate.statusCode()).as(duplicate::body).isEqualTo(409);
        assertThat(duplicate.body()).contains("\"field\"").contains("name");

        // Delete with the bumped version, then the fragment no longer lists the row.
        HttpResponse<String> deleted = postForm("/items/2/delete", cookie,
                Map.of("version", "2"));
        assertThat(deleted.statusCode()).as(deleted::body).isEqualTo(303);
        assertThat(deleted.headers().firstValue("Location")).contains("/items");
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

    private static HttpResponse<String> postForm(String path, String sessionCookie,
            Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
