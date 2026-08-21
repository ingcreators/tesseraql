package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
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
 * The stack ops shell, end to end (docs/stack-shells.md structural decision 2): one console per
 * stack at the origin scope, the switcher as the caller's {@code tql.ops.view.<name>} grants
 * applied to the member list, every page delegated over loopback to the selected member's own
 * runtime with the caller's session — and authorization staying at the member, so the shell adds
 * reach, never authority.
 *
 * <p>Ordered because the last test stops a member to observe the overview's per-member
 * degradation.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpsShellIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static MultiAppGateway gateway;
    static Path installRoot;

    /** Sees shop-a only ({@code tql.ops.view.shop-a}). */
    static Session viewerA;
    /** Sees shop-a, acts on shop-b ({@code tql.ops.view.shop-a} + {@code tql.ops.run.shop-b}). */
    static Session mixed;
    /** The full operator ({@code tql.ops.view.*} + {@code tql.ops.run.*}). */
    static Session operator;
    /** Authenticated, no ops atoms at all. */
    static Session noAtoms;

    record Session(String cookie, String csrf) {
    }

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-ops-shell-it");
        installApp("shop-a", "a");
        installApp("shop-b", "b");
        Files.writeString(installRoot.resolve(
                io.tesseraql.operations.app.StackSettings.FILE_NAME),
                """
                        framework:
                          datasource:
                            jdbcUrl: %s
                            username: %s
                            password: %s
                        """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword()));
        gateway = MultiAppGateway.start(installRoot, 0);
        viewerA = signIn("viewa");
        mixed = signIn("mixed");
        operator = signIn("operator");
        noAtoms = signIn("noatoms");
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        if (installRoot != null) {
            deleteRecursively(installRoot);
        }
    }

    /** The headline: the switcher is the grant, applied to the member list, deny by default. */
    @Test
    @Order(1)
    void theSwitcherIsTheGrantAppliedToTheMemberList() throws Exception {
        String home = get("/_tesseraql/ops/console", viewerA).body();
        assertThat(home).contains("/_tesseraql/ops/console/shop-a")
                .doesNotContain("/_tesseraql/ops/console/shop-b");

        // The granted member's delegated pages answer with the member's own data.
        HttpResponse<String> jobs = get("/_tesseraql/ops/console/shop-a/jobs", viewerA);
        assertThat(jobs.statusCode()).isEqualTo(200);
        assertThat(jobs.body()).contains("user.dailyMaintenance");

        // The other member's pages answer 404-shaped refusals through the shell, exactly as
        // an unknown resource answers (TQL-BATCH-4040).
        HttpResponse<String> denied = get("/_tesseraql/ops/console/shop-b", viewerA);
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(get("/_tesseraql/ops/console/shop-b/jobs", viewerA).statusCode())
                .isEqualTo(404);

        // No atoms: an empty switcher, and no member page at all.
        assertThat(get("/_tesseraql/ops/console", noAtoms).body())
                .contains("No applications in your operations scope");
        assertThat(get("/_tesseraql/ops/console/shop-a", noAtoms).statusCode()).isEqualTo(404);
    }

    /**
     * Authorization stays at the member: a delegated call forged past the shell — straight to
     * the member's internal port, with a caller whose grants do not cover that member — is
     * refused by the member's own scope check, not by shell cosmetics.
     */
    @Test
    @Order(2)
    void authorizationStaysAtTheMember() throws Exception {
        int port = gateway.host().port("shop-b");
        HttpResponse<String> forged = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/shop-b/_tesseraql/ops/data/overview"))
                .header("Cookie", viewerA.cookie())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(forged.statusCode()).isEqualTo(404);
        assertThat(forged.body()).contains("TQL-BATCH-4040");

        // The same caller, same endpoint, on the member their grant covers: answered.
        int portA = gateway.host().port("shop-a");
        HttpResponse<String> granted = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + portA + "/shop-a/_tesseraql/ops/data/overview"))
                .header("Cookie", viewerA.cookie())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(granted.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(granted.body()).path("version").asText()).isNotEmpty();
    }

    /**
     * The verbs are per application now: {@code tql.ops.view.a} + {@code tql.ops.run.b} sees a
     * and cannot act on it, can act on b — the asymmetry the retired two-axis model could not
     * express, pinned as its regression test.
     */
    @Test
    @Order(3)
    void viewBroadlyActNarrowly() throws Exception {
        // Sees a…
        assertThat(get("/_tesseraql/ops/console/shop-a/jobs", mixed).statusCode())
                .isEqualTo(200);
        // …cannot act on it: out of the run scope reads exactly like unknown.
        assertThat(postForm("/_tesseraql/ops/console/shop-a/jobs/run",
                "id=user.dailyMaintenance", mixed).statusCode()).isEqualTo(404);
        // …can act on b, even without seeing its pages.
        HttpResponse<String> started = postForm("/_tesseraql/ops/console/shop-b/jobs/run",
                "id=user.dailyMaintenance", mixed);
        assertThat(started.statusCode()).isEqualTo(303);
        assertThat(started.headers().firstValue("location").orElseThrow())
                .contains("/_tesseraql/ops/console/shop-b/executions/");
    }

    /** The full operator walks both members' pages, and actions record the actor. */
    @Test
    @Order(4)
    void theOperatorActsThroughTheShellAndTheMemberRecordsIt() throws Exception {
        String home = get("/_tesseraql/ops/console", operator).body();
        assertThat(home).contains("/_tesseraql/ops/console/shop-a")
                .contains("/_tesseraql/ops/console/shop-b");

        HttpResponse<String> started = postForm("/_tesseraql/ops/console/shop-a/jobs/run",
                "id=user.dailyMaintenance", operator);
        assertThat(started.statusCode()).isEqualTo(303);
        String location = started.headers().firstValue("location").orElseThrow();
        assertThat(location).contains("/_tesseraql/ops/console/shop-a/executions/");

        String detail = get(location, operator).body();
        assertThat(detail).contains("Job started.").contains("operator").contains(">manual<");
    }

    /**
     * The fan-out overview degrades per member, never whole: a stopped member's card says so
     * and the page renders — a shell that 500s because one member is mid-replace would
     * contradict Decision 29's requirement from the observing side. Last, because it stops
     * shop-b for good.
     */
    @Test
    @Order(5)
    void theOverviewDegradesPerMemberNeverWhole() throws Exception {
        gateway.host().app("shop-b").close();

        HttpResponse<String> home = get("/_tesseraql/ops/console", operator);
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("unreachable")
                .contains("/_tesseraql/ops/console/shop-a");
    }

    private static HttpResponse<String> get(String path, Session session) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + path))
                .header("Cookie", session.cookie())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A console form post: the hidden {@code _csrf} field, exactly as the pages render it. */
    private static HttpResponse<String> postForm(String path, String form, Session session)
            throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + path))
                .header("Cookie", session.cookie())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form + "&_csrf=" + session.csrf()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Signs in at the origin — the stack's one door — and keeps the session + CSRF token. */
    private static Session signIn(String loginId) throws Exception {
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
        return new Session(setCookie.substring(0, setCookie.indexOf(';')),
                MAPPER.readTree(login.body()).path("csrfToken").asText());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[]{"a", "b"}) {
                statement.execute("create schema " + schema);
                statement.execute("create table " + schema
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute(
                        "insert into " + schema + ".items (name) values ('from-" + schema + "')");
            }
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            seedUser(statement, hash, params, "viewa", List.of("tql.ops.view.shop-a"));
            seedUser(statement, hash, params, "mixed",
                    List.of("tql.ops.view.shop-a", "tql.ops.run.shop-b"));
            seedUser(statement, hash, params, "operator",
                    List.of("tql.ops.view.*", "tql.ops.run.*"));
            seedUser(statement, hash, params, "noatoms", List.of());
        }
    }

    /** One user, one personal role, the given atom grants — the standard role-permission join. */
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

    /**
     * One member: the user-admin example, renamed to its catalogue identity — the name is the
     * address, and the shell's delegation resolves members by it.
     */
    private static void installApp(String appId, String schema) throws IOException {
        Path appHome = installRoot.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        // Renamed, so its permission codes carry the new name too (TQL-YAML-1406).
        Path config = appHome.resolve("config/tesseraql.yml");
        Files.writeString(config, Files.readString(config)
                .replace("permission: user-admin.", "permission: " + appId + ".")
                .replace("name: user-admin", "name: " + appId));
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
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
