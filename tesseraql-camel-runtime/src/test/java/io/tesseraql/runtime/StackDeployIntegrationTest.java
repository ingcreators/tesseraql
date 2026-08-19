package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * The deploy surface, end to end (docs/stack-shells.md slice 3): a caller acquires a bearer at
 * the stack's origin — sign-in, then the token exchange, both validated against the stack file's
 * {@code security.jwt.*} — and deploys one application through the authenticated endpoint with a
 * scoped {@code tql.app.deploy.<name>} grant and no install-root access. The endpoint checks the
 * grant against the <b>package's declared name</b>, refuses before anything is written, and a
 * accepted deploy writes the same intent {@code tesseraql deploy} writes, which the running
 * host's reconciler converges to while the stack serves.
 *
 * <p>Ordered: the refusals run first, against the untouched v1 install; the accepted deploy then
 * moves the member to v2 and the stale-package refusal reads that state.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackDeployIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final AppInstaller INSTALLER = new AppInstaller();

    static MultiAppGateway gateway;
    static Path installRoot;
    static Path work;

    /** Holds {@code tql.app.deploy.shop} — exactly the one application, not the wildcard. */
    static String deployerBearer;
    /** Holds {@code tql.app.deploy.other} — a real deploy grant, for the wrong application. */
    static String otherBearer;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        work = Files.createTempDirectory("tesseraql-stack-deploy-work");
        installRoot = Files.createTempDirectory("tesseraql-stack-deploy-it");
        INSTALLER.install(packaged(appHome("1.0.0", "s1"), "1.0.0"), installRoot);
        Files.writeString(installRoot.resolve(
                io.tesseraql.operations.app.StackSettings.FILE_NAME),
                """
                        framework:
                          datasource:
                            jdbcUrl: %s
                            username: %s
                            password: %s
                        security:
                          jwt:
                            secret: stack-deploy-secret-0123456789abcdef
                            audience: https://stack.example.com
                            rolesClaim: roles
                            permissionsClaim: permissions
                          token:
                            enabled: true
                        """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword()));
        gateway = MultiAppGateway.start(installRoot, 0);
        deployerBearer = acquireBearer("deployer");
        otherBearer = acquireBearer("other");
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        deleteRecursively(installRoot);
        deleteRecursively(work);
    }

    /**
     * The grant is checked against the package's declared name — a genuine deploy grant for a
     * different application refuses, and nothing is written: no intent, catalogue untouched.
     */
    @Test
    @Order(1)
    void theGrantIsCheckedAgainstThePackagesDeclaredName() throws Exception {
        HttpResponse<String> refused = deploy(packaged(appHome("2.0.0", "s2"), "2.0.0"),
                otherBearer, "");
        assertThat(refused.statusCode()).isEqualTo(403);

        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .as("a refused deploy writes nothing")
                .isEqualTo("1.0.0");
        assertThat(Files.exists(installRoot.resolve(".upgrade").resolve("shop.json")))
                .isFalse();
    }

    /** No atom at all — a valid bearer whose grants say nothing about deploying — is a 403. */
    @Test
    @Order(2)
    void aBearerWithoutTheAtomIsRefused() throws Exception {
        HttpResponse<String> refused = deploy(packaged(appHome("2.0.0", "s2"), "2.0.0"),
                acquireBearer("nodeploy"), "");
        assertThat(refused.statusCode()).isEqualTo(403);
    }

    /**
     * The headline: token → upload → intent → reconciler → the new version serving, while the
     * stack never restarts. The bearer was acquired at the origin with the caller's own grants
     * riding it, and the endpoint's answer names what moved.
     */
    @Test
    @Order(3)
    void aScopedTokenDeploysOneApplicationEndToEnd() throws Exception {
        assertThat(itemName()).isEqualTo("s1");

        HttpResponse<String> accepted = deploy(packaged(appHome("2.0.0", "s2"), "2.0.0"),
                deployerBearer, "");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        var body = MAPPER.readTree(accepted.body());
        assertThat(body.path("name").asText()).isEqualTo("shop");
        assertThat(body.path("fromVersion").asText()).isEqualTo("1.0.0");
        assertThat(body.path("toVersion").asText()).isEqualTo("2.0.0");

        // The intent is written; the running host's reconciler converges to it.
        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .isEqualTo("2.0.0");
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if ("s2".equals(itemName())) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(itemName()).as("the reconciler replaced the serving runtime").isEqualTo("s2");
    }

    /** A package that is not newer than the installed version refuses — and writes nothing. */
    @Test
    @Order(4)
    void aStalePackageIsRefusedAndWritesNothing() throws Exception {
        HttpResponse<String> refused = deploy(packaged(appHome("1.0.0", "s1"), "1.0.0"),
                deployerBearer, "");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .isEqualTo("2.0.0");
    }

    /** A tampered upload is rejected by its declared SHA-256 before the preflight even runs. */
    @Test
    @Order(5)
    void aTamperedPackageIsRejectedByItsSha() throws Exception {
        HttpResponse<String> refused = deploy(packaged(appHome("3.0.0", "s1"), "3.0.0"),
                deployerBearer, "?sha256=" + "0".repeat(64));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .isEqualTo("2.0.0");
    }

    /**
     * The ops deploy page's display gate (docs/stack-shells.md, the deploy page): a browser
     * session holding a {@code tql.app.deploy} grant gets the page — its form posting to the
     * endpoint itself — and the console home's nav carries the entry; a session without one
     * meets the 404-shaped refusal and no nav entry, the deny-by-default the switcher already
     * has.
     */
    @Test
    @Order(6)
    void theDeployPageRendersOnlyForADeployGrantHolder() throws Exception {
        BrowserSession deployer = signIn("deployer");
        HttpResponse<String> page = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port()
                        + "/_tesseraql/ops/console/deploy"))
                .header("Cookie", deployer.cookie())
                .header("Accept", "text/html")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("action=\"/_tesseraql/deploy\"");
        assertThat(page.body()).as("lists the member the grant covers, with its version")
                .contains("shop").contains("2.0.0");

        HttpResponse<String> home = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + "/_tesseraql/ops/console"))
                .header("Cookie", deployer.cookie())
                .header("Accept", "text/html")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(home.body()).as("the nav entry renders for a holder")
                .contains("/_tesseraql/ops/console/deploy");

        BrowserSession nodeploy = signIn("nodeploy");
        HttpResponse<String> refused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port()
                        + "/_tesseraql/ops/console/deploy"))
                .header("Cookie", nodeploy.cookie())
                .header("Accept", "text/html")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).as("no atom, no page — 404-shaped like the switcher")
                .isEqualTo(404);
        HttpResponse<String> bareHome = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + "/_tesseraql/ops/console"))
                .header("Cookie", nodeploy.cookie())
                .header("Accept", "text/html")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bareHome.body()).doesNotContain("/_tesseraql/ops/console/deploy");
    }

    /**
     * A page that can make the browser POST must not be able to deploy: a session-cookie
     * multipart submit without the CSRF token is refused, and nothing is written.
     */
    @Test
    @Order(7)
    void aBrowserSubmitWithoutTheCsrfTokenIsRefused() throws Exception {
        BrowserSession session = signIn("deployer");
        HttpResponse<String> refused = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + "/_tesseraql/deploy"))
                .header("Cookie", session.cookie())
                .header("Accept", "text/html")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(packaged(appHome("9.0.0", "s1"), "9.0.0"), null)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .isEqualTo("2.0.0");
    }

    /**
     * The no-JS shape of the deploy page's form: a plain multipart post — the package as the
     * {@code file} part, the CSRF token as the {@code _csrf} field — answers post/redirect/get
     * back to the page with the result riding the query, and the reconciler converges the
     * stack to the new version exactly as for the bearer path.
     */
    @Test
    @Order(8)
    void aBrowserMultipartFormDeploysWithPostRedirectGet() throws Exception {
        BrowserSession session = signIn("deployer");
        HttpResponse<String> accepted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + "/_tesseraql/deploy"))
                .header("Cookie", session.cookie())
                .header("Accept", "text/html")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(packaged(appHome("3.0.0", "s1"), "3.0.0"), session.csrf())))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(303);
        assertThat(accepted.headers().firstValue("Location").orElseThrow())
                .isEqualTo("/_tesseraql/ops/console/deploy?deployed=shop"
                        + "&fromVersion=2.0.0&toVersion=3.0.0");

        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .isEqualTo("3.0.0");
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && !"s1".equals(itemName())) {
            Thread.sleep(200);
        }
        assertThat(itemName()).as("the reconciler replaced the serving runtime").isEqualTo("s1");
    }

    /**
     * The htmx shape of the same form ({@code installCsrfHeader} carries the token as the
     * {@code X-CSRF-Token} header): success answers 200 with {@code HX-Redirect} — htmx's
     * full-navigation signal — and no JSON body for the swap target.
     */
    @Test
    @Order(9)
    void anHtmxMultipartSubmitIsAnsweredWithHxRedirect() throws Exception {
        BrowserSession session = signIn("deployer");
        HttpResponse<String> accepted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + "/_tesseraql/deploy"))
                .header("Cookie", session.cookie())
                .header("HX-Request", "true")
                .header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipart(packaged(appHome("4.0.0", "s2"), "4.0.0"), null)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.headers().firstValue("HX-Redirect").orElseThrow())
                .isEqualTo("/_tesseraql/ops/console/deploy?deployed=shop"
                        + "&fromVersion=3.0.0&toVersion=4.0.0");
        assertThat(accepted.body()).isEmpty();
        assertThat(new AppCatalog(installRoot).find("shop").orElseThrow().version())
                .isEqualTo("4.0.0");
    }

    private static HttpResponse<String> deploy(Path tqlapp, String bearer, String query)
            throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + "/_tesseraql/deploy" + query))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(tqlapp))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A signed-in browser at the origin: the session cookie and its CSRF token. */
    record BrowserSession(String cookie, String csrf) {
    }

    private static BrowserSession signIn(String loginId) throws Exception {
        HttpResponse<String> login = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port()
                        + "/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"" + loginId + "\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        return new BrowserSession(setCookie.substring(0, setCookie.indexOf(';')),
                MAPPER.readTree(login.body()).path("csrfToken").asText());
    }

    /**
     * The acquisition path the design names (docs/stack-shells.md): sign in at the origin, then
     * exchange the session for a bearer at the origin's {@code /_tesseraql/token} — both served
     * by the surface runtime and signed with the stack file's key, the caller's own grants
     * riding the token's claims.
     */
    private static String acquireBearer(String loginId) throws Exception {
        BrowserSession session = signIn(loginId);
        HttpResponse<String> exchanged = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port()
                        + "/_tesseraql/token"))
                        .header("Cookie", session.cookie())
                        .header("X-CSRF-Token", session.csrf())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(exchanged.statusCode()).as(exchanged.body()).isEqualTo(200);
        return MAPPER.readTree(exchanged.body()).path("token").asText();
    }

    private static final String BOUNDARY = "tql-deploy-test-boundary";

    /**
     * The deploy page's form as bytes: the package as the {@code file} part and, when given,
     * the CSRF token as the {@code _csrf} field — the no-JS twin of the header.
     */
    private static byte[] multipart(Path tqlapp, String csrf) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        if (csrf != null) {
            bytes.writeBytes(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                    + csrf + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        bytes.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"app.tqlapp\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.writeBytes(Files.readAllBytes(tqlapp));
        bytes.writeBytes(("\r\n--" + BOUNDARY + "--\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    private static String itemName() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + gateway.port() + "/shop/api/items")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data").get(0).get("name").asText();
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String tag : new String[]{"s1", "s2"}) {
                statement.execute("create schema " + tag);
                statement.execute("create table " + tag
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + tag + ".items (name) values ('" + tag + "')");
            }
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            seedUser(statement, hash, params, "deployer", List.of("tql.app.deploy.shop"));
            seedUser(statement, hash, params, "other", List.of("tql.app.deploy.other"));
            seedUser(statement, hash, params, "nodeploy", List.of("tql.app.use.*"));
        }
    }

    private static void seedUser(Statement statement, String hash, String params, String loginId,
            List<String> permissions) throws Exception {
        statement.execute("insert into tql_users "
                + "(user_id, login_id, display_name, status, password_hash, password_algo,"
                + " password_params) values ('u-" + loginId + "','" + loginId + "','" + loginId
                + "','ACTIVE','" + hash + "','pbkdf2','" + params + "')");
        statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                + " values ('r-" + loginId + "','r-" + loginId + "','r-" + loginId + "')");
        statement.execute("insert into tql_user_roles (user_id, role_id)"
                + " values ('u-" + loginId + "','r-" + loginId + "')");
        for (String permission : permissions) {
            statement.execute("insert into tql_permissions"
                    + " (permission_id, permission_code, permission_name)"
                    + " values ('" + permission + "','" + permission + "','" + permission + "')"
                    + " on conflict do nothing");
            statement.execute("insert into tql_role_permissions (role_id, permission_id)"
                    + " values ('r-" + loginId + "','" + permission + "')");
        }
    }

    /** The user-admin example renamed to {@code shop}, with a version-marker items route. */
    private static Path appHome(String version, String schema) throws IOException {
        Path home = work.resolve("app-" + version + "-" + schema
                + "-" + System.nanoTime());
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, home, path));
        }
        // Renamed, so its permission codes carry the new name too (TQL-YAML-1406).
        Path exampleConfig = home.resolve("config/tesseraql.yml");
        Files.writeString(exampleConfig, Files.readString(exampleConfig)
                .replace("permission: user-admin.", "permission: shop."));
        Files.writeString(home.resolve("config/overlay.yml"), """
                tesseraql:
                  app:
                    name: shop
                    version: %s
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                """.formatted(version, POSTGRES.getJdbcUrl(), schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        Path itemsDir = home.resolve("web/api/items");
        Files.createDirectories(itemsDir);
        Files.writeString(itemsDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(itemsDir.resolve("list.sql"), "select id, name from items order by id\n");
        return home;
    }

    private static Path packaged(Path home, String version) throws IOException {
        Path pkg = work.resolve("pkg-" + version + "-" + System.nanoTime() + ".tqlapp");
        try (OutputStream stream = Files.newOutputStream(pkg);
                ZipOutputStream zip = new ZipOutputStream(stream);
                Stream<Path> files = Files.walk(home)) {
            files.filter(Files::isRegularFile).sorted().forEach(file -> {
                try {
                    zip.putNextEntry(
                            new ZipEntry(home.relativize(file).toString().replace('\\', '/')));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        return pkg;
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
