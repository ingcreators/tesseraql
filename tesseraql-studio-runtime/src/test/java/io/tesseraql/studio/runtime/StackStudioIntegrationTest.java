package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.runtime.DevMode;
import io.tesseraql.runtime.MultiAppGateway;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The studio shell, end to end (docs/studio-shell.md slice 3): a development stack serves one
 * Studio at the origin — the switcher filtered by the caller's {@code tql.studio.edit} atoms,
 * every page delegated to the member's workshop with the caller's own credentials, and an edit
 * applied through the shell observable as the member's reloaded route. The workshop is
 * topology: the same stack under {@code host} (no DevMode) mounts nothing Studio-shaped, and
 * no configuration changes that.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackStudioIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static MultiAppGateway gateway;
    private static Path stackDir;
    private static String editorCookie;
    private static String viewerCookie;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        stackDir = Files.createTempDirectory("tesseraql-studio-shell-it");
        // The workspace shape: source homes one level down plus the stack marker — the one
        // shape whose workshop the host says yes to (docs/studio-shell.md structural
        // decision 1).
        Files.writeString(stackDir.resolve("tesseraql-stack.yml"), "# stack marker\n");
        installApp("shop-a", "a");
        gateway = MultiAppGateway.start(stackDir, 0, new MultiAppGateway.Settings(), null,
                new DevMode(null, "http://localhost:0"));
        editorCookie = signIn("admin");
        viewerCookie = signIn("viewer");
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        deleteRecursively(stackDir);
    }

    @Test
    @Order(1)
    void anonymousBrowsersBounceToTheOriginSignIn() throws Exception {
        HttpResponse<String> denied = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(uri("/_tesseraql/studio"))
                        .header("Accept", "text/html").build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(302);
        assertThat(denied.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/login?redirect=");
    }

    @Test
    @Order(2)
    void theSwitcherListsOnlyTheCallersWorkshops() throws Exception {
        HttpResponse<String> editor = get("/_tesseraql/studio", editorCookie);
        assertThat(editor.statusCode()).isEqualTo(200);
        assertThat(editor.body()).contains("href=\"/_tesseraql/studio/shop-a/ui\"");

        // Deny-by-default: no tql.studio.edit atoms, no entries — and no fence crossing.
        HttpResponse<String> viewer = get("/_tesseraql/studio", viewerCookie);
        assertThat(viewer.statusCode()).isEqualTo(200);
        assertThat(viewer.body()).doesNotContain("/_tesseraql/studio/shop-a/ui")
                .contains("No applications in your workshop scope");
        assertThat(get("/_tesseraql/studio/shop-a/ui", viewerCookie).statusCode())
                .as("a page for a member outside the caller's atoms is 404-shaped")
                .isEqualTo(404);
    }

    @Test
    @Order(3)
    void aDelegatedPageRendersTheMembersWorkshop() throws Exception {
        HttpResponse<String> explorer = get("/_tesseraql/studio/shop-a/ui", editorCookie);
        assertThat(explorer.statusCode()).as(explorer.body()).isEqualTo(200);
        assertThat(explorer.body()).contains("<meta name=\"csrf-token\"");
        // The shared templates stay member-agnostic; every studio link they emit carries the
        // member segment (the link builder's one-place rule).
        assertThat(explorer.body()).contains("/_tesseraql/studio/shop-a/ui/docs");
        assertThat(matches(explorer.body(), "(?:href|action)=\"(/_tesseraql/studio/ui[^\"]*)\""))
                .as("no member-less studio address leaks")
                .isEmpty();
    }

    @Test
    @Order(4)
    void anEditThroughTheShellServesImmediately() throws Exception {
        String csrf = csrfOf(get("/_tesseraql/studio/shop-a/ui", editorCookie).body());
        String route = """
                version: tesseraql/v1
                id: shell.hello
                kind: route
                recipe: page
                security:
                  auth: public
                response:
                  html:
                    template: hello.html
                """;
        // Save a draft through the shell, then apply it: the member writes its own source
        // tree and hot-reloads, and the new route answers on the member's prefix.
        assertThat(postForm("/_tesseraql/studio/shop-a/ui/save", editorCookie, csrf,
                "path=" + enc("web/shell-hello/get.yml") + "&content=" + enc(route))
                .statusCode()).isIn(200, 303);
        assertThat(postForm("/_tesseraql/studio/shop-a/ui/save", editorCookie, csrf,
                "path=" + enc("web/shell-hello/hello.html")
                        + "&content=" + enc("<p>hello from the workshop</p>"))
                .statusCode()).isIn(200, 303);
        assertThat(postForm("/_tesseraql/studio/shop-a/ui/drafts/apply-all", editorCookie, csrf,
                "").statusCode()).isIn(200, 303);

        HttpResponse<String> served = get("/shop-a/shell-hello", null);
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat(served.body()).contains("hello from the workshop");
    }

    @Test
    @Order(5)
    void theMemberRefusesAForgedDirectCall() throws Exception {
        // Authority stays at the member: the workshop API under the member's own prefix
        // re-checks the caller's atoms, so a caller the shell would never list is refused
        // by the member itself — assert the member's refusal, not the shell's.
        assertThat(get("/shop-a/_tesseraql/studio/data/studio.explorer", viewerCookie)
                .statusCode()).isEqualTo(404);
        assertThat(get("/shop-a/_tesseraql/studio/data/studio.explorer", editorCookie)
                .statusCode()).isEqualTo(200);
    }

    @Test
    @Order(6)
    void aHostShapedStackMountsNothingStudioShaped() throws Exception {
        // The same stack, host-shaped (no DevMode): no shell at the origin, no workshop API
        // on the member, and no configuration turns either on (structural decision 1).
        try (MultiAppGateway host = MultiAppGateway.start(stackDir, 0)) {
            String cookie = signInAt(host.port(), "admin");
            assertThat(getAt(host.port(), "/_tesseraql/studio", cookie).statusCode())
                    .isEqualTo(404);
            assertThat(getAt(host.port(), "/shop-a/_tesseraql/studio/data/studio.explorer",
                    cookie).statusCode()).isEqualTo(404);
        }
    }

    private static String csrfOf(String html) {
        Matcher matcher = Pattern.compile("name=\"csrf-token\" content=\"([^\"]+)\"")
                .matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static java.util.List<String> matches(String html, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(html);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return java.util.List.copyOf(found);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String signIn(String loginId) throws Exception {
        return signInAt(gateway.port(), loginId);
    }

    private static String signInAt(int port, String loginId) throws Exception {
        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/shop-a/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"" + loginId + "\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + gateway.port() + path);
    }

    private static HttpResponse<String> get(String path, String cookie) throws Exception {
        return getAt(gateway.port(), path, cookie);
    }

    private static HttpResponse<String> getAt(int port, String path, String cookie)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String cookie, String csrf,
            String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
    }

    /** The application's schema plus the framework schema its sessions and identity live in. */
    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[]{"a", "shared"}) {
                statement.execute("create schema " + schema);
            }
        }
        seedIdentity("shared");
    }

    /** Two accounts: the editor holds the studio atom; the viewer holds everything else. */
    private static void seedIdentity(String schema) throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, status,"
                    + " password_hash, password_algo, password_params) values"
                    + " ('u1','admin','Administrator','ACTIVE','" + hash + "','pbkdf2','"
                    + params + "'),"
                    + " ('u2','viewer','Viewer','ACTIVE','" + hash + "','pbkdf2','"
                    + params + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name) values"
                    + " ('r1','EDITOR','Editor'), ('r2','VIEWER','Viewer')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values"
                    + " ('u1','r1'), ('u2','r2')");
            for (String atom : new String[]{"tql.app.use.*", "tql.ops.view.*",
                    "tql.studio.edit.*"}) {
                statement.execute("insert into tql_permissions"
                        + " (permission_id, permission_code, permission_name)"
                        + " values ('" + atom + "','" + atom + "','" + atom + "')");
                statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                        + " values ('r1','" + atom + "')");
            }
            for (String atom : new String[]{"tql.app.use.*"}) {
                statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                        + " values ('r2','" + atom + "')");
            }
        }
    }

    /** A source-tree member (the workspace shape), its own schema, shared framework state. */
    private static void installApp(String appName, String schema) throws IOException {
        Path appHome = stackDir.resolve(appName);
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(appHome);
        rewritePolicyCodes(appHome, appName);
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %1$s&currentSchema=%2$s
                    username: %3$s
                    password: %4$s
                tesseraql:
                  app:
                    name: %5$s
                """.formatted(POSTGRES.getJdbcUrl(), schema, POSTGRES.getUsername(),
                POSTGRES.getPassword(), appName));
        Files.writeString(appHome.resolve("config/overlay.yml"), """
                tesseraql:
                  datasources:
                    shared:
                      jdbcUrl: %1$s&currentSchema=shared
                      username: %2$s
                      password: %3$s
                  framework:
                    datasource: shared
                  sessions:
                    store: jdbc
                  identity:
                    realms:
                      local:
                        datasource: shared
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** The policy-code namespace fence (TQL-YAML-1406): codes carry the renamed app's name. */
    private static void rewritePolicyCodes(Path appHome, String appName) throws IOException {
        try (Stream<Path> files = Files.walk(appHome)) {
            files.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .forEach(path -> {
                        try {
                            String text = Files.readString(path);
                            String rewritten = text
                                    .replace("user-admin.", appName + ".")
                                    .replace("name: user-admin", "name: " + appName);
                            if (!rewritten.equals(text)) {
                                Files.writeString(path, rewritten);
                            }
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        }
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
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
