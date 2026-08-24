package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The headline activation arrangement (docs/application-roles.md structural decisions 4 and 5):
 * one user with two concurrent roles in one member, two "tabs" — two paths under {@code _as}
 * segments sharing one session cookie — interleaved without mixing, because the address is the
 * only carrier. Policies read the active view while the fence reads the union; a forged segment
 * can narrow, never widen (TQL-SEC-4148); emitted links carry the segment and asset URLs do
 * not; the audit rows say which capacity acted; and a token minted {@code --as} carries the
 * active view plus the {@code acting_role} claim.
 */
@Testcontainers
class StackActivationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Two concurrent roles scoped to shop-a (sales carries users.read, audit does not). */
    static Session kenji;
    /** Exactly one role scoped to shop-a — choice of one is no choice. */
    static Session solo;

    static MultiAppGateway gateway;
    static Path installRoot;

    record Session(String cookie, String csrf) {
    }

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-stack-activation-it");
        installApp("shop-a", "a");
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
        kenji = signIn("kenji");
        solo = signIn("solo");
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

    @Test
    void aMultiRoleBrowserEntryRedirectsToThePickerWhichListsTheHeldRoles() throws Exception {
        HttpResponse<String> entry = get("/shop-a/admin/users", kenji);
        assertThat(entry.statusCode()).isEqualTo(302);
        assertThat(entry.headers().firstValue("Location").orElse(""))
                .isEqualTo("/_tesseraql/roles?app=shop-a&redirect=%2Fshop-a%2Fadmin%2Fusers");

        HttpResponse<String> picker = get(
                "/_tesseraql/roles?app=shop-a&redirect=%2Fshop-a%2Fadmin%2Fusers", kenji);
        assertThat(picker.statusCode()).isEqualTo(200);
        assertThat(picker.body())
                .contains("/shop-a/_as/shop-a.sales/admin/users")
                .contains("/shop-a/_as/shop-a.audit/admin/users")
                .contains("販売担当")
                .contains("監査担当");
    }

    /**
     * The headline: two tabs, two roles, interleaved requests on one session cookie, zero
     * mixing. The union holds {@code shop-a.users.read} (the sales bundle), so the audit tab's
     * 403 proves the policy read the ACTIVE view, not the union — and the trail says which
     * capacity acted.
     */
    @Test
    void twoTabsTwoRolesInterleaveWithoutMixing() throws Exception {
        HttpResponse<String> salesTab = get("/shop-a/_as/shop-a.sales/admin/users", kenji);
        assertThat(salesTab.statusCode()).isEqualTo(200);
        // Emitted links carry the segment (the base model variable); asset URLs do not.
        assertThat(salesTab.body()).contains("/shop-a/_as/shop-a.sales/");
        assertThat(salesTab.body()).doesNotContain("/shop-a/_as/shop-a.sales/assets/");
        // The switcher offers the other capacity as a direct link that swaps the segment.
        assertThat(salesTab.body()).contains("/shop-a/_as/shop-a.audit/admin/users");

        HttpResponse<String> auditTab = get("/shop-a/_as/shop-a.audit/admin/users", kenji);
        assertThat(auditTab.statusCode()).isEqualTo(403);

        // The first tab keeps its role: nothing the second tab did reaches it.
        assertThat(get("/shop-a/_as/shop-a.sales/admin/users", kenji).statusCode())
                .isEqualTo(200);

        List<String> actingRoles = actingRolesAudited("/admin/users");
        assertThat(actingRoles).contains("shop-a.sales", "shop-a.audit");
    }

    @Test
    void aSingleRoleHolderIsActivatedByRedirect() throws Exception {
        HttpResponse<String> entry = get("/shop-a/admin/users", solo);
        assertThat(entry.statusCode()).isEqualTo(302);
        assertThat(entry.headers().firstValue("Location").orElse(""))
                .isEqualTo("/shop-a/_as/shop-a.sales/admin/users");
        assertThat(get("/shop-a/_as/shop-a.sales/admin/users", solo).statusCode())
                .isEqualTo(200);
    }

    /**
     * The redirects carry the query once. {@code request().uri()} is path <em>plus</em> query,
     * and the locations append the query themselves — reading the full URI doubled it
     * ({@code ?tab=a?tab=a}), on the activation redirect and inside the picker's
     * {@code redirect} parameter alike.
     */
    @Test
    void anActivationRedirectCarriesTheQueryOnce() throws Exception {
        HttpResponse<String> activated = get("/shop-a/admin/users?tab=audit", solo);
        assertThat(activated.statusCode()).isEqualTo(302);
        assertThat(activated.headers().firstValue("Location").orElse(""))
                .isEqualTo("/shop-a/_as/shop-a.sales/admin/users?tab=audit");

        HttpResponse<String> picker = get("/shop-a/admin/users?tab=audit", kenji);
        assertThat(picker.statusCode()).isEqualTo(302);
        assertThat(picker.headers().firstValue("Location").orElse("")).isEqualTo(
                "/_tesseraql/roles?app=shop-a&redirect=%2Fshop-a%2Fadmin%2Fusers%3Ftab%3Daudit");
    }

    @Test
    void aForgedRoleNarrowsNeverWidens() throws Exception {
        // A browser gets the picker (the human fix is choosing again) …
        HttpResponse<String> browser = get("/shop-a/_as/shop-a.boss/admin/users", kenji);
        assertThat(browser.statusCode()).isEqualTo(302);
        assertThat(browser.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/roles?app=shop-a");
        // … an API caller gets the 403 with its own code.
        HttpResponse<String> api = getJson("/shop-a/_as/shop-a.boss/admin/users", kenji);
        assertThat(api.statusCode()).isEqualTo(403);
        assertThat(api.body()).contains("TQL-SEC-4148");
    }

    /** Absence denies: a bare-address non-HTML caller runs with no application role active. */
    @Test
    void anApiCallerWithoutTheSegmentHoldsNoApplicationRole() throws Exception {
        assertThat(getJson("/shop-a/admin/users", kenji).statusCode()).isEqualTo(403);
        assertThat(getJson("/shop-a/_as/shop-a.sales/admin/users", kenji).statusCode())
                .isEqualTo(200);
    }

    /** Claim-asserted principals have no attribution to activate; the union stays theirs. */
    @Test
    void aBearerWithClaimRolesIsRefusedActivationButKeepsItsUnion() throws Exception {
        String bearer = token(List.of("tql.app.use.shop-a", "shop-a.users.read"));
        HttpResponse<String> activated = bearerGet("/shop-a/_as/shop-a.sales/api/users",
                bearer);
        assertThat(activated.statusCode()).isEqualTo(403);
        assertThat(activated.body()).contains("TQL-SEC-4148");
        assertThat(bearerGet("/shop-a/api/users", bearer).statusCode()).isEqualTo(200);
    }

    /** The token face: minted {@code --as} carries the active view and says the capacity. */
    @Test
    void aTokenMintedAsARoleCarriesTheActiveViewAndTheClaim() throws Exception {
        HttpResponse<String> minted = postJson("/shop-a/_tesseraql/token",
                "{\"actingRole\":\"shop-a.sales\"}", kenji);
        assertThat(minted.statusCode()).isEqualTo(200);
        JsonNode payload = jwtPayload(MAPPER.readTree(minted.body()).get("token").asText());
        assertThat(payload.get("acting_role").asText()).isEqualTo("shop-a.sales");
        List<String> roles = strings(payload.get("roles"));
        assertThat(roles).contains("r-kenji", "shop-a.sales").doesNotContain("shop-a.audit");
        List<String> permissions = strings(payload.get("permissions"));
        assertThat(permissions).contains("shop-a.users.read", "tql.app.use.shop-a");

        // Nothing selected mints the union, exactly as before.
        HttpResponse<String> union = postJson("/shop-a/_tesseraql/token", "{}", kenji);
        assertThat(union.statusCode()).isEqualTo(200);
        JsonNode unionPayload = jwtPayload(MAPPER.readTree(union.body()).get("token").asText());
        assertThat(unionPayload.has("acting_role")).isFalse();
        assertThat(strings(unionPayload.get("roles"))).contains("shop-a.sales", "shop-a.audit");

        // An unheld role is a refusal, not a wider token.
        assertThat(postJson("/shop-a/_tesseraql/token",
                "{\"actingRole\":\"shop-a.boss\"}", kenji).statusCode()).isEqualTo(403);
    }

    private static List<String> actingRolesAudited(String pathContains) throws Exception {
        List<String> roles = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select distinct acting_role from a.tql_route_audit"
                                + " where url_path like '%" + pathContains
                                + "%' and acting_role is not null")) {
            while (rs.next()) {
                roles.add(rs.getString(1));
            }
        }
        return roles;
    }

    private static JsonNode jwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        }
        return values;
    }

    private static HttpResponse<String> get(String path, Session session) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path))
                .header("Accept", "text/html")
                .header("Cookie", session.cookie())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getJson(String path, Session session) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path))
                .header("Accept", "application/json")
                .header("Cookie", session.cookie())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String path, String body, Session session)
            throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> bearerGet(String path, String token) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + gateway.port() + path);
    }

    /**
     * Context conditions compose with activation rather than competing with it
     * (docs/access-governance.md structural decision 8): the conditions step runs before
     * activation, so a grant this request's network does not admit is gone from the active
     * view, gone from the picker, and unreachable through its own {@code _as} segment.
     *
     * <p>Two roles differing only in the network they name is the whole assertion: if the drop
     * were a blanket one rather than a filter, the day role would go with the night role.
     */
    @Test
    void aRoleDeniedByItsNetworkConditionLeavesTheActiveViewAndThePicker() throws Exception {
        Session hazel = signIn("hazel");

        // Two roles held, one usable here: choice of one is no choice, so the browser entry
        // lands in the surviving role rather than at the picker.
        HttpResponse<String> entry = get("/shop-a/admin/users", hazel);
        assertThat(entry.statusCode()).isEqualTo(302);
        assertThat(entry.headers().firstValue("Location").orElse(""))
                .isEqualTo("/shop-a/_as/shop-a.day/admin/users");

        HttpResponse<String> picker = get("/_tesseraql/roles?app=shop-a", hazel);
        assertThat(picker.body()).contains("shop-a.day").doesNotContain("shop-a.night");

        assertThat(get("/shop-a/_as/shop-a.day/admin/users", hazel).statusCode())
                .as("the role whose condition this request satisfies is fully usable")
                .isEqualTo(200);

        // Asking to act as the dropped role is asking for a capacity the caller no longer
        // holds — the same answer a forged segment gets, because it is the same check.
        HttpResponse<String> night = get("/shop-a/_as/shop-a.night/admin/users", hazel);
        assertThat(night.statusCode()).isEqualTo(302);
        assertThat(night.headers().firstValue("Location").orElse(""))
                .startsWith("/_tesseraql/roles?app=shop-a");
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
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
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
            statement.execute("create schema a");
            statement.execute("set search_path to a");
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("set search_path to public");
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }

            // The two application roles of shop-a: sales reads users, audit does not — the
            // difference the two tabs observe.
            statement.execute("insert into tql_roles (role_id, role_code, role_name,"
                    + " application) values ('r-sales','shop-a.sales','販売担当','shop-a')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name,"
                    + " application) values ('r-audit','shop-a.audit','監査担当','shop-a')");
            for (String permission : List.of("shop-a.users.read", "shop-a.read")) {
                statement.execute("insert into tql_permissions"
                        + " (permission_id, permission_code, permission_name) values ('"
                        + permission + "','" + permission + "','" + permission + "')");
            }
            statement.execute("insert into tql_role_permissions values"
                    + " ('r-sales','shop-a.users.read')");
            statement.execute("insert into tql_role_permissions values ('r-sales','shop-a.read')");
            statement.execute("insert into tql_role_permissions values ('r-audit','shop-a.read')");

            seedUser(statement, hash, params, "kenji", List.of("tql.app.use.shop-a"));
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('u-kenji','r-sales')");
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('u-kenji','r-audit')");
            seedUser(statement, hash, params, "solo", List.of("tql.app.use.shop-a"));
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('u-solo','r-sales')");

            // Context conditions (docs/access-governance.md structural decision 8): two more
            // shop-a roles differing only in the network their condition names — one this
            // loopback client is inside, one it is not.
            statement.execute("insert into tql_roles (role_id, role_code, role_name,"
                    + " application) values ('r-day','shop-a.day','日勤','shop-a')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name,"
                    + " application) values ('r-night','shop-a.night','夜勤','shop-a')");
            statement.execute("insert into tql_role_permissions values"
                    + " ('r-day','shop-a.users.read')");
            statement.execute("insert into tql_role_permissions values ('r-night','shop-a.read')");
            statement.execute("insert into tql_role_conditions values"
                    + " ('r-day','network','127.0.0.0/8')");
            statement.execute("insert into tql_role_conditions values"
                    + " ('r-night','network','203.0.113.0/24')");
            seedUser(statement, hash, params, "hazel", List.of("tql.app.use.shop-a"));
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('u-hazel','r-day')");
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('u-hazel','r-night')");
        }
    }

    /** One user, one personal stack-wide role, the given grants. */
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

    /** One member: the user-admin example, renamed, with route audit and token issuing on. */
    private static void installApp(String appId, String schema) throws IOException {
        Path appHome = installRoot.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(appHome);
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
                tesseraql:
                  audit:
                    routes:
                      enabled: true
                  security:
                    token:
                      enabled: true
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
