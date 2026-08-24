package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared-stack hosting, end to end: two applications behind one gateway, addressed as
 * {@code /<name>/} on one origin, each in its own runtime
 * (docs/app-isolation-model.md decision 2, docs/base-path.md slice 6).
 *
 * <p>This is the case docs/base-path.md opened with. A stack-hosted HTML page returned 200 and was
 * unusable — its stylesheet, its scripts and its login form all named the origin, where the
 * gateway answers 404 — and the multi-app tests missed it because they exercised a JSON route,
 * which emits no links and so survives a prefix by accident. So these tests ask for pages, and
 * then ask for what the pages name.
 */
@Testcontainers
class StackModeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static MultiAppGateway gateway;
    private static Path installRoot;
    private static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-stack-it");
        installApp("shop-a", "a");
        installApp("shop-b", "b");
        gateway = MultiAppGateway.start(installRoot, 0);
        sessionCookie = signIn();
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        deleteRecursively(installRoot);
    }

    /**
     * The page the design opened with: every URL it emits is asked for, and answers.
     *
     * <p>Asserting on the markup alone is what let the defect through — a page can name a
     * perfectly-formed address that nothing serves.
     */
    @Test
    void anApplicationPageUnderThePrefixIsUsable() throws Exception {
        HttpResponse<String> page = get("/shop-a/users", sessionCookie);

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(originRootedUrlsIn(page.body()))
                .as("the only URLs addressing the origin are the stack's own surfaces —"
                        + " nothing of the application leaks unprefixed")
                .allMatch(url -> url.startsWith("/_tesseraql/"));
        for (String url : prefixedUrlsIn(page.body())) {
            assertThat(get(url, sessionCookie).statusCode()).as(url).isEqualTo(200);
        }
    }

    /** An htmx swap is a second request for a fragment, and it goes to the prefixed address. */
    @Test
    void anHtmxFragmentSwapsThroughThePrefix() throws Exception {
        HttpResponse<String> fragment = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/shop-a/users/fragments/table"))
                        .header("Cookie", sessionCookie)
                        .header("HX-Request", "true")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(fragment.statusCode()).isEqualTo(200);
        assertThat(fragment.body()).contains("<table");
        assertThat(originRootedUrlsIn(fragment.body())).isEmpty();
    }

    /**
     * One sign-in across the stack (docs/app-isolation-model.md decision 2): the session
     * established through one application's prefix authenticates the next one, because the
     * gateway issued the cookie at the origin root and the runtimes share a framework database.
     */
    @Test
    void oneSignInReachesEveryApplicationInTheStack() throws Exception {
        assertThat(get("/shop-a/users/fragments/table", sessionCookie).statusCode())
                .isEqualTo(200);
        assertThat(get("/shop-b/users/fragments/table", sessionCookie).statusCode())
                .as("the same session, the neighbouring application")
                .isEqualTo(200);

        assertThat(get("/shop-b/users/fragments/table", null).statusCode())
                .as("and it is a session that authorizes, not the stack")
                .isEqualTo(401);
    }

    /**
     * The portal (docs/root-portal.md): anonymous browser users meet the stack's sign-in at the
     * origin scope with a {@code redirect} that brings them back; signed in, one screen lists the
     * members at their derived addresses — and the session that authorizes it is the same one
     * the members share.
     */
    @Test
    void thePortalListsTheStacksApplications() throws Exception {
        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/_tesseraql/portal"))
                        .header("Accept", "text/html")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(anonymous.headers().firstValue("Location").orElseThrow())
                .startsWith("/_tesseraql/login?redirect=%2F_tesseraql%2Fportal");

        HttpResponse<String> portal = get("/_tesseraql/portal", sessionCookie);
        assertThat(portal.statusCode()).isEqualTo(200);
        assertThat(portal.body())
                .contains("href=\"/shop-a\"")
                .contains("href=\"/shop-b\"");
        // Every navigable URL the page names beside the tiles is the surface's own scope —
        // shell assets, the account chip — and at the origin those are real addresses now, so
        // each must answer. POST targets (the chip's toggles) are not navigated here.
        for (String url : matches(portal.body(), "(?:href|src)=\"(/[^\"#?]+)\"")) {
            if (url.equals("/shop-a") || url.equals("/shop-b")) {
                continue;
            }
            assertThat(get(url, sessionCookie).statusCode()).as(url).isEqualTo(200);
        }
    }

    // "Studio is usable through its application's prefix" moved to the workshop module's
    // StackStudioIntegrationTest (docs/studio-shell.md slice 1): this module's test classpath
    // no longer carries Studio, which is the extraction's whole point.

    /** An unauthenticated browser on a member page is bounced to the stack's origin sign-in —
     * the member serves no door of its own — with a {@code redirect} carrying the prefixed
     * address it was going to (docs/stack-shells.md structural decision 3). */
    @Test
    void theLoginBounceGoesToTheStacksOrigin() throws Exception {
        HttpResponse<String> denied = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(uri("/shop-a/users/board"))
                        .header("Accept", "text/html").build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(denied.statusCode()).isEqualTo(302);
        assertThat(denied.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/login?redirect=")
                .as("the redirect target is the prefixed address the browser asked for, once")
                .endsWith("%2Fshop-a%2Fusers%2Fboard");
    }

    /**
     * {@code href}/{@code src}/{@code action} values that address the origin, not the app —
     * anything rooted outside the application's derived {@code /<name>} prefix, now that the
     * address is the name rather than an {@code /apps/} wrapper.
     */
    private static List<String> originRootedUrlsIn(String html) {
        return matches(html, "(?:href|src|action|formaction|data-value|sse-connect"
                + "|hx-get|hx-post)=\"(/(?!shop-a/|shop-b/)[^\"]*)\"");
    }

    /** The application's own URLs: prefixed, and therefore addresses the gateway can be asked. */
    private static List<String> prefixedUrlsIn(String html) {
        return matches(html, "(?:href|src)=\"(/shop-a/[^\"#?]+)\"");
    }

    private static List<String> matches(String html, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(html);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return List.copyOf(found);
    }

    /** Signs in through one application's prefix, returning the stack-wide session cookie. */
    private static String signIn() throws Exception {
        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/shop-a/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie)
                .as("a stack shares one sign-in, so its cookie is not scoped to one app's prefix")
                .contains("Path=/;");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + gateway.port() + path);
    }

    private static HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Each application owns a schema; the framework's own state — sessions and the identity
     * realm — lives in a schema they share, which is what makes one sign-in reach both.
     */
    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[]{"a", "b", "shared"}) {
                statement.execute("create schema " + schema);
            }
        }
        // The applications' own tables are theirs: each runtime migrates its schema at startup.
        // Only the framework's schema is seeded here, with the one account the stack signs in as.
        seedIdentity("shared");
    }

    /** The managed identity realm and its one account, in the schema a runtime reads it from. */
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
                    + " password_hash, password_algo, password_params)"
                    + " values ('u1','admin','Administrator','ACTIVE','" + hash + "','pbkdf2','"
                    + params + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r1','USER_READ','User Read')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
            // The stack's atoms (docs/stack-shells.md structural decision 1): the member fence
            // and the portal read tql.app.use, the console link tql.ops.view, the IAM Admin
            // link its store-wide pair — this account exercises pages, so it holds them all.
            for (String atom : new String[]{"tql.app.use.*", "tql.ops.view.*",
                    "tql.iam.admin.view", "tql.iam.admin.write"}) {
                statement.execute("insert into tql_permissions"
                        + " (permission_id, permission_code, permission_name)"
                        + " values ('" + atom + "','" + atom + "','" + atom + "')");
                statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                        + " values ('r1','" + atom + "')");
            }
        }
    }

    private static void installApp(String appId, String schema) throws IOException {
        installApp(installRoot, appId, schema, true);
    }

    /**
     * @param sharedFrameworkState whether the application's sessions and identity realm live in
     *                             the schema its neighbours share — the shape of a stack. Under
     *                             independent hosting they stay in the application's own.
     */
    private static void installApp(Path root, String appId, String schema,
            boolean sharedFrameworkState) throws IOException {
        Path appHome = root.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(appHome);
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %1$s&currentSchema=%2$s
                    username: %3$s
                    password: %4$s
                """.formatted(POSTGRES.getJdbcUrl(), schema, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // The stack's shape (docs/app-isolation-model.md decision 2): each application keeps its
        // own business schema, and the framework's own state — sessions, the identity realm —
        // lives in one they share, which is what a shared sign-in is made of. Written as an
        // overlay, which is merged last, so the example app's own config is left alone.
        if (sharedFrameworkState) {
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

        new AppCatalog(root).register(new InstalledApp(
                appId, "1.0.0", appId + "/1.0.0", List.of()));
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
