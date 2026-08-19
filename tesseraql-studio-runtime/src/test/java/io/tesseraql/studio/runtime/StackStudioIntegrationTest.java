package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import io.tesseraql.runtime.MultiAppGateway;
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
 * Studio under the gateway, from the workshop module's side: a member-mounted Studio is reached
 * through its application's prefix, and everything it emits — asset URLs, the CSRF token the kit
 * posts back, the command palette's navigation targets — is prefixed with it
 * (docs/app-isolation-model.md slice 5). The property lived in the runtime module's
 * StackModeIntegrationTest until the extraction (docs/studio-shell.md slice 1) took Studio off
 * that module's classpath; this test pins it from the one classpath that still mounts the
 * workshop.
 */
@Testcontainers
class StackStudioIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static MultiAppGateway gateway;
    private static Path installRoot;
    private static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-stack-studio-it");
        installApp("shop-a", "a");
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
     * The page the prefix can break: the member's Studio answers under {@code /shop-a/} and
     * every address it names is served by this runtime. Whether this caller may open each is a
     * separate question — the ops shell needs tql.ops.view atoms — so the test asks only that
     * the address exists.
     */
    @Test
    void studioIsUsableThroughItsApplicationsPrefix() throws Exception {
        HttpResponse<String> studio = get("/shop-a/_tesseraql/studio/ui", sessionCookie);

        assertThat(studio.statusCode()).isEqualTo(200);
        assertThat(studio.body()).contains("<meta name=\"csrf-token\"");
        // The origin-scope addresses a member page carries are exactly the stack's own
        // surfaces (docs/stack-shells.md structural decisions 2 and 3): the operations
        // console, IAM Admin, and the account family — everything else stays base-relative.
        assertThat(originRootedUrlsIn(studio.body()))
                .containsOnly("/_tesseraql/ops/console", "/_tesseraql/admin/users",
                        "/_tesseraql/account", "/_tesseraql/account/pins/toggle");
        assertThat(studio.body())
                .as("the command palette navigates to addresses this runtime serves")
                .contains("data-value=\"/shop-a/_tesseraql/studio/ui/docs\"");
        for (String url : prefixedUrlsIn(studio.body())) {
            assertThat(get(url, sessionCookie).statusCode()).as(url).isNotEqualTo(404);
        }
    }

    /** The member Studio page bounces anonymous browsers to the stack's origin sign-in. */
    @Test
    void anAnonymousStudioRequestBouncesToTheOrigin() throws Exception {
        HttpResponse<String> denied = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(uri("/shop-a/_tesseraql/studio/ui"))
                        .header("Accept", "text/html").build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(denied.statusCode()).isEqualTo(302);
        assertThat(denied.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/login?redirect=")
                .endsWith("%2Fshop-a%2F_tesseraql%2Fstudio%2Fui");
    }

    /**
     * {@code href}/{@code src}/{@code action} values that address the origin, not the app —
     * anything rooted outside the application's derived {@code /<name>} prefix.
     */
    private static List<String> originRootedUrlsIn(String html) {
        return matches(html, "(?:href|src|action|formaction|data-value|sse-connect"
                + "|hx-get|hx-post)=\"(/(?!shop-a/)[^\"]*)\"");
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

    /** Signs in through the application's prefix, returning the stack-wide session cookie. */
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

    /** The managed identity realm and its one account, in the schema the runtime reads it from. */
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
            // The member fence and the pages this account opens read the stack's atoms
            // (docs/stack-shells.md structural decision 1).
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
        Path appHome = installRoot.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
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
        // The stack's shape: the application keeps its own business schema, and the framework's
        // own state — sessions, the identity realm — lives in the shared one.
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

        new AppCatalog(installRoot).register(new InstalledApp(
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
