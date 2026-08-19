package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
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
 * The identity remainder and the {@code tql.app.use} fence, end to end (docs/stack-shells.md
 * structural decision 3): one sign-in door and one admin door at the stack's origin scope, the
 * hosted members' own copies gone, the 401 bounce origin-absolute with a {@code redirect} that
 * returns to the member page — and reach into an application a property of the principal's
 * {@code tql.app.use.<name>} grant, refused at the member's fence and mirrored by the portal's
 * tiles, so what a user sees and what they can reach are one answer.
 */
@Testcontainers
class StackIdentityIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static MultiAppGateway gateway;
    static Path installRoot;

    /** May use shop-a only ({@code tql.app.use.shop-a}); both apps' own policies pass. */
    static Session userA;
    /** Authenticated with the apps' own codes but no {@code tql.app.use} grant at all. */
    static Session noUse;
    /** The wildcard user plus the identity store's own door ({@code tql.iam.admin.*}). */
    static Session admin;

    record Session(String cookie, String csrf) {
    }

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-stack-identity-it");
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
        userA = signIn("usera");
        noUse = signIn("nouse");
        admin = signIn("admin");
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

    /**
     * The bounce: an unauthenticated browser navigation on a member page 302s to the stack's
     * origin sign-in, carrying the original <em>prefixed</em> path as {@code redirect}; signing
     * in there returns to the member page that bounced; and the member serves no sign-in page of
     * its own anymore.
     */
    @Test
    void theLoginBounceGoesToTheOriginAndReturnsToTheMember() throws Exception {
        HttpResponse<String> denied = CLIENT.send(
                HttpRequest.newBuilder(uri("/shop-a/admin/users"))
                        .header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(302);
        assertThat(denied.headers().firstValue("Location").orElse(""))
                .isEqualTo("/_tesseraql/login?redirect=%2Fshop-a%2Fadmin%2Fusers");

        HttpResponse<String> loginPage = get("/_tesseraql/login?redirect=%2Fshop-a%2Fadmin%2Fusers",
                null);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        assertThat(loginPage.body())
                .contains("name=\"redirect\"")
                .contains("value=\"/shop-a/admin/users\"");

        HttpResponse<String> signedIn = CLIENT.send(
                HttpRequest.newBuilder(uri("/_tesseraql/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "loginId=usera&password=s3cret&redirect="
                                        + URLEncoder.encode("/shop-a/admin/users",
                                                StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(signedIn.statusCode()).isEqualTo(303);
        assertThat(signedIn.headers().firstValue("Location").orElse(""))
                .isEqualTo("/shop-a/admin/users");

        assertThat(get("/shop-a/_tesseraql/login", null).statusCode())
                .as("the member's own login page copy is gone (the JSON POST transport"
                        + " remains a Java route, so GET answers 405 there)")
                .isIn(404, 405);
    }

    /**
     * The fence: reach is the grant. The same principal, the same session, the same policies
     * passing in both applications — only {@code tql.app.use.<member>} distinguishes them, and
     * it is the member itself that refuses, before any route.
     */
    @Test
    void theFenceRefusesAnAuthenticatedPrincipalWithoutTheGrant() throws Exception {
        assertThat(get("/shop-a/users/fragments/table", userA).statusCode()).isEqualTo(200);
        assertThat(get("/shop-b/users/fragments/table", userA).statusCode())
                .as("the neighbour refuses the same session without its grant")
                .isEqualTo(403);
        assertThat(get("/shop-a/users/fragments/table", noUse).statusCode())
                .as("deny by default: no tql.app.use grants, no reach")
                .isEqualTo(403);
    }

    /** A route declaring {@code auth: public} answers either way — a public page stays public. */
    @Test
    void aPublicRouteIsUntouchedByTheFence() throws Exception {
        assertThat(get("/shop-a/users", null).statusCode()).isEqualTo(200);
        assertThat(get("/shop-a/users", noUse).statusCode()).isEqualTo(200);
    }

    /** A JWT service caller meets the same fence, because a principal is a principal. */
    @Test
    void aServiceCallerMeetsTheSameFence() throws Exception {
        HttpResponse<String> granted = bearerGet("/shop-a/api/users",
                token(List.of("tql.app.use.shop-a")));
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);

        assertThat(bearerGet("/shop-a/api/users", token(List.of())).statusCode())
                .as("the same token without the atom")
                .isEqualTo(403);
    }

    /**
     * The portal's tiles read the same atom beside tenant entitlement: what a user sees and what
     * they can reach are one answer, deny by default.
     */
    @Test
    void thePortalTilesFilterByTheAppUseGrant() throws Exception {
        String forUserA = get("/_tesseraql/portal", userA).body();
        assertThat(forUserA).contains("href=\"/shop-a\"").doesNotContain("href=\"/shop-b\"");

        String forNoUse = get("/_tesseraql/portal", noUse).body();
        assertThat(forNoUse)
                .doesNotContain("href=\"/shop-a\"")
                .doesNotContain("href=\"/shop-b\"")
                .contains("No applications are reachable from here.");

        String forAdmin = get("/_tesseraql/portal", admin).body();
        assertThat(forAdmin).contains("href=\"/shop-a\"").contains("href=\"/shop-b\"");
    }

    /**
     * One admin door to one store: IAM Admin answers at the origin behind
     * {@code tql.iam.admin.view}, and no member serves {@code /_tesseraql/admin} of its own.
     */
    @Test
    void iamAdminAnswersAtTheOriginBehindItsAtom() throws Exception {
        HttpResponse<String> forAdmin = get("/_tesseraql/admin/users", admin);
        assertThat(forAdmin.statusCode()).as(forAdmin.body()).isEqualTo(200);
        assertThat(forAdmin.body()).contains("usera");

        assertThat(get("/_tesseraql/admin/users", userA).statusCode())
                .as("the store-wide atom, not signing in, is the authority")
                .isEqualTo(403);

        assertThat(get("/shop-a/_tesseraql/admin/users", admin).statusCode())
                .as("the member's own IAM Admin copy is gone")
                .isEqualTo(404);
    }

    /**
     * The per-application grant views (docs/application-roles.md slice 1): the applications
     * page lists the stack's members, and one member's page answers "who may do what" from the
     * store — exact grants under the member's own atom, wildcard holders under the wildcard
     * row, and the application's own permission codes with their holders and paths.
     */
    @Test
    void theGrantViewsAnswerPerApplication() throws Exception {
        HttpResponse<String> list = get("/_tesseraql/admin/applications", admin);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).contains("shop-a").contains("shop-b")
                .contains("tql.app.use.*");

        HttpResponse<String> shopA = get("/_tesseraql/admin/applications/shop-a", admin);
        assertThat(shopA.statusCode()).as(shopA.body()).isEqualTo(200);
        // usera's exact grant sits under the member's atom; admin's wildcard under the
        // wildcard row; the app's own code lists every holder with its delivering role.
        assertThat(shopA.body()).contains("tql.app.use.shop-a").contains("usera")
                .contains("tql.app.use.*").contains("admin")
                .contains("shop-a.users.read").contains("nouse").contains("r-nouse");

        HttpResponse<String> shopB = get("/_tesseraql/admin/applications/shop-b", admin);
        assertThat(shopB.statusCode()).isEqualTo(200);
        // Nobody holds shop-b's exact use atom: the wildcard row alone carries reach.
        assertThat(shopB.body()).doesNotContain("tql.app.use.shop-b</code>")
                .contains("tql.app.use.*");
    }

    /** The views are IAM surfaces: no store-wide atom, no page; an unknown name is 404. */
    @Test
    void theGrantViewsRefuseAndRefuseToGuess() throws Exception {
        assertThat(get("/_tesseraql/admin/applications", userA).statusCode()).isEqualTo(403);
        assertThat(get("/_tesseraql/admin/applications/nope", admin).statusCode())
                .as("an application outside the stack is unknown, not empty")
                .isEqualTo(404);
    }

    /**
     * The account surface is the stack's: it answers at the origin, the member's copy is gone,
     * and a member page links it origin-absolute so the one door is the one that is linked.
     */
    @Test
    void theAccountSurfaceLivesAtTheOrigin() throws Exception {
        assertThat(get("/_tesseraql/account", userA).statusCode()).isEqualTo(200);
        assertThat(get("/shop-a/_tesseraql/account", userA).statusCode()).isEqualTo(404);

        String memberPage = get("/shop-a/admin/users", userA).body();
        assertThat(memberPage).contains("href=\"/_tesseraql/account\"");
    }

    private static HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Accept", "text/html");
        if (session != null) {
            request.header("Cookie", session.cookie());
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> bearerGet(String path, String token) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + gateway.port() + path);
    }

    /** Signs in at the origin — the stack's one door. */
    private static Session signIn(String loginId) throws Exception {
        HttpResponse<String> login = CLIENT.send(
                HttpRequest.newBuilder(uri("/_tesseraql/login"))
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

    /** An HS256 bearer for the fixture app's declared secret, carrying the given atom grants. */
    private static String token(List<String> permissions) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "svc-1", "roles", List.of("USER_READ"),
                        "permissions", permissions))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[]{"a", "b"}) {
                statement.execute("create schema " + schema);
                // The member's own admin page reads its app-side identity contracts from its
                // main schema; empty tables are enough for the page to render its shell.
                statement.execute("set search_path to " + schema);
                for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                        .split(";")) {
                    if (!ddl.isBlank()) {
                        statement.execute(ddl);
                    }
                }
                statement.execute("set search_path to public");
            }
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            // Every user passes the applications' own policies (the app codes below), so the
            // 403s the tests observe isolate the fence: only the tql.app.use grants differ.
            seedUser(statement, hash, params, "usera",
                    List.of("tql.app.use.shop-a", "shop-a.users.read", "shop-b.users.read"));
            seedUser(statement, hash, params, "nouse",
                    List.of("shop-a.users.read", "shop-b.users.read"));
            seedUser(statement, hash, params, "admin",
                    List.of("tql.app.use.*", "tql.iam.admin.view", "tql.iam.admin.write",
                            "shop-a.users.read", "shop-b.users.read"));
        }
    }

    /** One user, one personal role, the given grants — the standard role-permission join. */
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

    /** One member: the user-admin example, renamed to its catalogue identity. */
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
