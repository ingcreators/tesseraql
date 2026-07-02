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
 * Acceptance for the declarative, role-filtered application sidebar menu (Slice 1). An app that ships
 * a {@code config/menu.yml} has its items rendered into the shell's nav slot, filtered
 * <em>server-side</em> to the caller's roles/permissions — a gated item a caller may not see is never
 * emitted (not merely CSS-hidden). An app with no {@code menu.yml} keeps rendering its hand-authored
 * {@code templates/nav.html}.
 */
@Testcontainers
class AppMenuIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime menuRuntime;
    static TesseraqlRuntime plainRuntime;
    static Path menuAppHome;
    static Path plainAppHome;

    @BeforeAll
    static void start() throws Exception {
        menuAppHome = prepareAppHome(true);
        plainAppHome = prepareAppHome(false);
        menuRuntime = TesseraqlRuntime.start(menuAppHome, freePort());
        plainRuntime = TesseraqlRuntime.start(plainAppHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (menuRuntime != null) {
            menuRuntime.close();
        }
        if (plainRuntime != null) {
            plainRuntime.close();
        }
        deleteRecursively(menuAppHome);
        deleteRecursively(plainAppHome);
    }

    @Test
    void theMenuIsRoleFilteredServerSideForTheAuthenticatedCaller() throws Exception {
        // The bearer token carries the USER_WRITE role and no permissions.
        HttpResponse<String> response = get(menuRuntime, "/menu-probe",
                Map.of("Authorization", "Bearer " + token()));

        assertThat(response.statusCode()).isEqualTo(200);
        // Public item and the USER_WRITE-gated item are emitted.
        assertThat(response.body())
                .contains("href=\"/probe-home\"").contains("MenuHome")
                .contains("href=\"/probe-users\"").contains("MenuUsers");
        // The SUPERADMIN-gated and permission-gated items are absent from the HTML entirely.
        assertThat(response.body())
                .doesNotContain("/probe-secret").doesNotContain("MenuSecret")
                .doesNotContain("/probe-perm").doesNotContain("MenuPerm");
        // The declarative menu takes the nav slot, so the app's hand-authored nav is not rendered.
        assertThat(response.body()).doesNotContain("User Management (IAM)");
    }

    @Test
    void aMenuEditIsLiveOnTheNextRenderWithoutARestart() throws Exception {
        Path menuFile = menuAppHome.resolve("config/menu.yml");
        String original = Files.readString(menuFile);
        try {
            // The item does not exist yet.
            assertThat(get(menuRuntime, "/menu-probe", Map.of("Authorization", "Bearer " + token()))
                    .body()).doesNotContain("/probe-live");

            // Edit config/menu.yml on the running app — no reload, no restart.
            Files.writeString(menuFile, original + "  - { label: MenuLive, href: /probe-live }\n");

            // The very next render reflects it (MenuSpec.live re-reads on a size/mtime change).
            assertThat(get(menuRuntime, "/menu-probe", Map.of("Authorization", "Bearer " + token()))
                    .body()).contains("href=\"/probe-live\"").contains("MenuLive");
        } finally {
            Files.writeString(menuFile, original);
        }
    }

    @Test
    void aFeatureFlagIsInjectedIntoTheContextAndServedLive() throws Exception {
        Path flags = menuAppHome.resolve("config/flags.yml");
        try {
            Files.writeString(flags, "flags:\n  probeFlag: LIVE-ALPHA\n");
            assertThat(get(menuRuntime, "/menu-probe",
                    Map.of("Authorization", "Bearer " + token())).body()).contains("LIVE-ALPHA");

            // Change the flag on the running app — the next request reflects it (no restart).
            Files.writeString(flags, "flags:\n  probeFlag: LIVE-BRAVO\n");
            assertThat(get(menuRuntime, "/menu-probe",
                    Map.of("Authorization", "Bearer " + token())).body())
                    .contains("LIVE-BRAVO").doesNotContain("LIVE-ALPHA");
        } finally {
            Files.deleteIfExists(flags);
        }
    }

    @Test
    void aFlagGatesCommandRouteValidationLive() throws Exception {
        Path flags = menuAppHome.resolve("config/flags.yml");
        try {
            // Flag on → the guarded (always-false) rule fires → 422; the command never runs.
            Files.writeString(flags, "flags:\n  blockAll: true\n");
            assertThat(postJson(menuRuntime, "/api/flag-gate").statusCode()).isEqualTo(422);

            // Flag off (live edit) → the rule is skipped → the command runs → 200.
            Files.writeString(flags, "flags:\n  blockAll: false\n");
            assertThat(postJson(menuRuntime, "/api/flag-gate").statusCode()).isEqualTo(200);
        } finally {
            Files.deleteIfExists(flags);
        }
    }

    @Test
    void anAnonymousCallerSeesOnlyPublicMenuItems() throws Exception {
        // /users is a public page (no security), so no principal is resolved.
        HttpResponse<String> response = get(menuRuntime, "/users", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("href=\"/probe-home\"");
        assertThat(response.body())
                .doesNotContain("/probe-users")
                .doesNotContain("/probe-secret")
                .doesNotContain("/probe-perm");
    }

    @Test
    void anAppWithoutMenuYamlStillRendersItsHandAuthoredNav() throws Exception {
        HttpResponse<String> response = get(plainRuntime, "/users", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        // The shared templates/nav.html fragment renders, and no declarative menu leaks in.
        assertThat(response.body())
                .contains("User Management (IAM)")
                .doesNotContain("/probe-home");
    }

    private static HttpResponse<String> get(TesseraqlRuntime runtime, String path,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(TesseraqlRuntime runtime, String path)
            throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin", "roles",
                        List.of("USER_WRITE"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome(boolean withMenu) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-menu-it");
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
        if (withMenu) {
            Files.writeString(target.resolve("config/menu.yml"), """
                    menu:
                      - { label: MenuHome,   href: /probe-home,   icon: home }
                      - { label: MenuUsers,  href: /probe-users,  roles: [USER_WRITE] }
                      - { label: MenuSecret, href: /probe-secret, roles: [SUPERADMIN] }
                      - { label: MenuPerm,   href: /probe-perm,   permissions: [some.perm] }
                    """);
            // A bearer-authenticated shell page, so the request carries a principal with roles.
            Path probe = Files.createDirectories(target.resolve("web/menu-probe"));
            Files.writeString(probe.resolve("get.yml"), """
                    version: tesseraql/v1
                    id: tql.test.menu-probe
                    kind: route
                    recipe: page

                    security:
                      auth: bearer
                      policy: users.write

                    response:
                      html:
                        status: 200
                        template: index.html
                        model:
                          flag: flags.probeFlag
                    """);
            Files.writeString(probe.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html xmlns:th="http://www.thymeleaf.org"
                          th:replace="~{tql/shell :: shell('Menu Probe', \
                    ~{templates/nav.html :: app-nav}, ~{}, ~{:: #page-content})}">
                    <div id="page-content" class="hc-stack">
                      <section class="hc-card"><h2>Menu Probe</h2>
                        <p th:text="${flag}">flag</p></section>
                    </div>
                    </html>
                    """);
            // A command route whose validation is gated by a flag — proves flags reach command routes
            // (the transactional command processor evaluates validate against the binder's context).
            Path gate = Files.createDirectories(target.resolve("web/api/flag-gate"));
            Files.writeString(gate.resolve("post.yml"), """
                    version: tesseraql/v1
                    id: tql.test.flag-gate
                    kind: route
                    recipe: command-json

                    security:
                      auth: bearer
                      policy: users.write

                    validate:
                      gated:
                        when: flags.blockAll
                        rule: "false"
                        field: gate
                        code: blocked

                    sql:
                      file: flag-gate.sql
                      mode: update

                    response:
                      json:
                        body:
                          ok: true
                    """);
            Files.writeString(gate.resolve("flag-gate.sql"),
                    "insert into users (name, status) values ('flag-probe', 'ACTIVE')\n");
        }
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
        if (root == null || !Files.exists(root)) {
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
