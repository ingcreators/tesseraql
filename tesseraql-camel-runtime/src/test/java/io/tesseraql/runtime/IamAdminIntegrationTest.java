package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.identity.DefaultIdentityPack;
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
 * Integration test for the bundled IAM admin console app (design ch. 10, 32): the yaml/sql/template
 * app shipped in tesseraql-identity mounts automatically, serving the user list, a per-user detail
 * page (roles, groups, permissions) and post/redirect/get status actions; callers without a bearer
 * principal are denied.
 */
@Testcontainers
class IamAdminIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    // The iam-admin UI is now browser-session auth; requests carry this admin session cookie + CSRF.
    static String adminCookie;
    static String adminCsrf;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext().getRegistry()
                .lookupByNameAndType(io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String sid = sessions.create(new io.tesseraql.security.Principal("iam-admin", "iam-admin",
                "IAM Admin", null, List.of(), List.of("ADMIN"),
                List.of("tql.iam.admin.view", "tql.iam.admin.write"), Map.of()),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
        adminCookie = sessions.cookieName() + "=" + sid;
        adminCsrf = sessions.session(sid).csrfToken();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void listsUsersForAuthorizedCaller() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/admin/users", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        assertThat(response.body()).contains("IAM Admin").contains("admin").contains("bob");
        assertThat(response.body()).contains("/_tesseraql/admin/users/u1");
    }

    /** Slice 5: the list narrows server-side by login/display-name/email contains. */
    @Test
    void usersListFiltersByLoginNameOrEmail() throws Exception {
        String byLogin = get("/_tesseraql/admin/users?q=bob", true).body();
        assertThat(byLogin).contains(">bob<").doesNotContain(">admin<");
        // Email matches too (only u1 carries admin@example.com).
        String byEmail = get("/_tesseraql/admin/users?q=example.com", true).body();
        assertThat(byEmail).contains(">admin<").doesNotContain(">bob<");
        assertThat(get("/_tesseraql/admin/users?q=matches-nothing", true).body())
                .contains("No users found.");
    }

    @Test
    void showsUserDetailWithRolesGroupsPermissions() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/admin/users/u1", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Administrator");
        assertThat(response.body()).contains("USER_READ");
        assertThat(response.body()).contains("OPS");
        assertThat(response.body()).contains("users:read");
    }

    @Test
    void disableThenEnableUserViaForm() throws Exception {
        // The detail page offers the status actions as plain form posts; the destructive one is
        // guarded by the hc confirm behavior (data-hc-confirm dialog before submit).
        HttpResponse<String> before = get("/_tesseraql/admin/users/u2", true);
        assertThat(before.body()).contains("Disable user")
                .contains("/_tesseraql/admin/users/u2/disable")
                .contains(
                        "data-hc-confirm=\"Disable user bob? Their active sessions end immediately.\"");

        // post/redirect/get: the command answers 303 back to the detail page with the
        // flash flag, and the landing page confirms out loud.
        HttpResponse<String> disabled = post("/_tesseraql/admin/users/u2/disable");
        assertThat(disabled.statusCode()).isEqualTo(303);
        assertThat(disabled.headers().firstValue("location"))
                .hasValue("/_tesseraql/admin/users/u2?disabled=1");
        assertThat(get("/_tesseraql/admin/users/u2?disabled=1", true).body())
                .contains("User disabled.").contains("DISABLED");

        HttpResponse<String> enabled = post("/_tesseraql/admin/users/u2/enable");
        assertThat(enabled.statusCode()).isEqualTo(303);
        assertThat(enabled.headers().firstValue("location"))
                .hasValue("/_tesseraql/admin/users/u2?enabled=1");
        assertThat(get("/_tesseraql/admin/users/u2?enabled=1", true).body())
                .contains("User enabled.").contains("ACTIVE");
    }

    /**
     * Session administration (docs/session-administration.md): the detail page lists the
     * subject's sessions (timestamps only), "Sign out everywhere" ends them, and disabling —
     * per-user and bulk alike — invalidates instead of waiting for cookie expiry.
     */
    @Test
    void sessionsPanelRevokesAndDisableEndsSessions() throws Exception {
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext()
                .getRegistry().lookupByNameAndType(
                        io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        try {
            sessions.create(bob(), io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
            String detail = get("/_tesseraql/admin/users/u2", true).body();
            assertThat(detail).contains("Active sessions").contains("1 active session")
                    .contains("/_tesseraql/admin/users/u2/sessions/revoke");

            HttpResponse<String> revoke = post("/_tesseraql/admin/users/u2/sessions/revoke");
            assertThat(revoke.statusCode()).isEqualTo(303);
            assertThat(sessions.sessionsFor("u2")).isEmpty();
            assertThat(get("/_tesseraql/admin/users/u2", true).body())
                    .contains("No active sessions");

            // Disabled means disabled: the session dies with the status flip.
            sessions.create(bob(), io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
            assertThat(post("/_tesseraql/admin/users/u2/disable").statusCode()).isEqualTo(303);
            assertThat(sessions.sessionsFor("u2")).isEmpty();

            // Bulk disable invalidates the same way.
            post("/_tesseraql/admin/users/u2/enable");
            sessions.create(bob(), io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
            assertThat(postForm("/_tesseraql/admin/users/bulk", "action=disable&ids=u2")
                    .statusCode()).isEqualTo(303);
            assertThat(sessions.sessionsFor("u2")).isEmpty();
        } finally {
            post("/_tesseraql/admin/users/u2/enable");
        }
    }

    /**
     * One device by its handle (docs/session-visibility.md): the panel shows the device
     * facts, revoking one row ends exactly that session, and a handle from another
     * subject deletes nothing.
     */
    @Test
    void sessionsPanelSignsOutOneDevice() throws Exception {
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext()
                .getRegistry().lookupByNameAndType(
                        io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String laptop = sessions.create(bob(),
                new io.tesseraql.security.session.SessionStore.ClientInfo(
                        "Mozilla/5.0 (X11; Linux)", "203.0.113.7"));
        String phone = sessions.create(bob(),
                new io.tesseraql.security.session.SessionStore.ClientInfo(
                        "Mozilla/5.0 (iPhone)", "198.51.100.2"));
        try {
            String detail = get("/_tesseraql/admin/users/u2", true).body();
            assertThat(detail).contains("Mozilla/5.0 (X11; Linux)").contains("203.0.113.7")
                    .contains("/_tesseraql/admin/users/u2/sessions/revoke-one")
                    .contains("Last active");
            // The cookie id itself never reaches the page.
            assertThat(detail).doesNotContain(laptop).doesNotContain(phone);

            String laptopHandle = sessions.sessionsFor("u2").stream()
                    .filter(s -> "203.0.113.7".equals(s.remoteAddr()))
                    .findFirst().orElseThrow().handle();
            // A handle posted at the wrong subject deletes nothing.
            assertThat(postForm("/_tesseraql/admin/users/u1/sessions/revoke-one",
                    "handle=" + laptopHandle).statusCode()).isEqualTo(303);
            assertThat(sessions.sessionsFor("u2")).hasSize(2);

            HttpResponse<String> revoked = postForm(
                    "/_tesseraql/admin/users/u2/sessions/revoke-one",
                    "handle=" + laptopHandle);
            assertThat(revoked.statusCode()).isEqualTo(303);
            assertThat(sessions.session(laptop)).isNull();
            assertThat(sessions.session(phone)).isNotNull();
        } finally {
            sessions.invalidateOthersFor("u2", "");
        }
    }

    /**
     * The cross-subject sessions page (docs/session-visibility.md): live rows across
     * subjects with device facts, a subject-prefix filter, and a per-row sign-out.
     */
    @Test
    void sessionsPageListsFiltersAndRevokesAcrossSubjects() throws Exception {
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext()
                .getRegistry().lookupByNameAndType(
                        io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String bobPhone = sessions.create(bob(),
                new io.tesseraql.security.session.SessionStore.ClientInfo(
                        "Mozilla/5.0 (iPhone)", "198.51.100.9"));
        try {
            String page = get("/_tesseraql/admin/sessions", true).body();
            assertThat(page).contains("Active sessions").contains(">u2<")
                    .contains("Mozilla/5.0 (iPhone)").contains("198.51.100.9")
                    .contains("/_tesseraql/admin/users/u2")
                    .doesNotContain(bobPhone);

            // The filter narrows to the prefix; the admin's own session drops out.
            String filtered = get("/_tesseraql/admin/sessions?q=u2", true).body();
            assertThat(filtered).contains(">u2<").doesNotContain(">iam-admin<");

            String handle = sessions.sessionsFor("u2").get(0).handle();
            HttpResponse<String> revoked = postForm("/_tesseraql/admin/sessions/revoke",
                    "subject=u2&handle=" + handle);
            assertThat(revoked.statusCode()).isEqualTo(303);
            assertThat(revoked.headers().firstValue("location").orElse(""))
                    .isEqualTo("/_tesseraql/admin/sessions?signedout=1");
            assertThat(get("/_tesseraql/admin/sessions?signedout=1", true).body())
                    .contains("Device signed out.");
            assertThat(sessions.session(bobPhone)).isNull();
        } finally {
            sessions.invalidateOthersFor("u2", "");
        }
    }

    private static io.tesseraql.security.Principal bob() {
        return new io.tesseraql.security.Principal("u2", "bob", "Bob", null,
                List.of(), List.of(), List.of(), Map.of());
    }

    @Test
    void writeRequiresAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/admin/users/u2/disable"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    /** Bulk disable (hc datagrid-bulk-actions, docs/hypermedia-ui.md "Bulk actions"). */
    @Test
    void bulkDisableTakesTheSelectionAndRedirectsBack() throws Exception {
        // The list wears the recipe markup: selection checkboxes posting repeated ids,
        // a nameless select-all, and the confirm-gated toolbar the kit reveals.
        HttpResponse<String> list = get("/_tesseraql/admin/users", true);
        assertThat(list.body()).contains("data-hc-datagrid-actions=\"#iam-users\"")
                .contains("data-hc-datagrid-count")
                .contains("name=\"ids\" value=\"u1\"")
                .contains("aria-label=\"Select all\"");
        try {
            HttpResponse<String> bulk = postForm("/_tesseraql/admin/users/bulk",
                    "action=disable&ids=u1&ids=u2");
            assertThat(bulk.statusCode()).isEqualTo(303);
            assertThat(bulk.headers().firstValue("location"))
                    .hasValue("/_tesseraql/admin/users?bulk=2&selected=2");
            assertThat(get("/_tesseraql/admin/users?bulk=2&selected=2", true).body())
                    .contains("2 user(s) disabled.");
            assertThat(get("/_tesseraql/admin/users/u1", true).body()).contains("DISABLED");
            assertThat(get("/_tesseraql/admin/users/u2", true).body()).contains("DISABLED");
        } finally {
            post("/_tesseraql/admin/users/u1/enable");
            post("/_tesseraql/admin/users/u2/enable");
        }
    }

    /**
     * A stale selection cannot read as a completed action: an id matching no user updates no
     * rows, so it is not counted, and the banner says how many of the selection actually
     * changed (docs/silent-tolerance.md O10 — the count used to be the request size).
     */
    @Test
    void bulkDisableReportsWhatChangedNotWhatWasSelected() throws Exception {
        try {
            HttpResponse<String> bulk = postForm("/_tesseraql/admin/users/bulk",
                    "action=disable&ids=u1&ids=no-such-user");
            assertThat(bulk.statusCode()).isEqualTo(303);
            assertThat(bulk.headers().firstValue("location"))
                    .hasValue("/_tesseraql/admin/users?bulk=1&selected=2");
            assertThat(get("/_tesseraql/admin/users?bulk=1&selected=2", true).body())
                    .contains("1 of 2 selected user(s) disabled");
        } finally {
            post("/_tesseraql/admin/users/u1/enable");
        }
    }

    /** The selection is client state: an empty one and an unknown verb are refused. */
    @Test
    void bulkRefusesAnEmptySelectionAndAnUnknownAction() throws Exception {
        assertThat(postForm("/_tesseraql/admin/users/bulk", "action=disable")
                .statusCode()).isEqualTo(400);
        assertThat(postForm("/_tesseraql/admin/users/bulk", "action=vaporize&ids=u2")
                .statusCode()).isEqualTo(400);
        assertThat(get("/_tesseraql/admin/users/u2", true).body()).contains("ACTIVE");
    }

    /** The bulk endpoint rides the same gates as the per-user writes. */
    @Test
    void bulkRequiresAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/admin/users/bulk"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("action=disable&ids=u2"))
                .build();
        assertThat(HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    /**
     * The grant trail end to end (docs/access-governance.md slice 1): the actor reaches the
     * history row from the session principal, through the route's declared parameter — the
     * one link in the chain that only a live request can prove.
     */
    @Test
    void aRoleAssignmentIsRecordedWithTheAdministratorWhoMadeIt() throws Exception {
        assertThat(postForm("/_tesseraql/admin/users/u2/roles/assign", "roleCode=USER_READ")
                .statusCode()).isEqualTo(303);

        String history = get("/_tesseraql/admin/history?user=u2", true).body();
        assertThat(history).contains("USER_READ").contains("role-granted")
                .contains("iam-admin").contains("bob");

        assertThat(postForm("/_tesseraql/admin/users/u2/roles/unassign", "roleCode=USER_READ")
                .statusCode()).isEqualTo(303);
        assertThat(get("/_tesseraql/admin/history?user=u2", true).body())
                .contains("role-revoked");

        // The per-user card is the same trail, narrowed to the person being looked at.
        assertThat(get("/_tesseraql/admin/users/u2", true).body())
                .contains("Grant history").contains("role-revoked");
        // Another person's page does not show it.
        assertThat(get("/_tesseraql/admin/users/u1", true).body())
                .doesNotContain("role-revoked");
    }

    /**
     * The separation-of-duties surface (docs/access-governance.md slice 2): declared on its
     * page, enforced in the grant write, and the people already on both sides reported.
     */
    @Test
    void aSeparationOfDutiesConstraintIsDeclaredAndThenEnforced() throws Exception {
        assertThat(postForm("/_tesseraql/admin/roles/create",
                "code=sod.left&name=Left&application=").statusCode()).isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/roles/create",
                "code=sod.right&name=Right&application=").statusCode()).isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/constraints/create",
                "name=Left+and+right&severity=block&firstRole=sod.left&secondRole=sod.right")
                .statusCode()).isEqualTo(303);

        String page = get("/_tesseraql/admin/constraints", true).body();
        assertThat(page).contains("Left and right").contains("sod.left").contains("sod.right");

        assertThat(postForm("/_tesseraql/admin/users/u2/roles/assign", "roleCode=sod.left")
                .statusCode()).isEqualTo(303);
        // The second side is refused, and the refusal names the constraint.
        HttpResponse<String> refused = postForm("/_tesseraql/admin/users/u2/roles/assign",
                "roleCode=sod.right");
        assertThat(refused.statusCode()).isNotEqualTo(303);
        assertThat(refused.body()).contains("Left and right");

        // The violation report shows the one side that did land, so the constraint is
        // visible against grants that predate it.
        assertThat(get("/_tesseraql/admin/constraints", true).body())
                .contains("sod.left").contains("bob");
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", adminCookie)
                .header("X-CSRF-Token", adminCsrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rendersNotFoundPageForUnknownUser() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/admin/users/missing", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("User not found.");
    }

    @Test
    void requiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/admin/users", false).statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (auth) {
            request.header("Authorization", "Bearer " + token()).header("Cookie", adminCookie);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Cookie", adminCookie)
                .header("X-CSRF-Token", adminCsrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(TestClaims.addressed(TestClaims
                        .addressed(Map.of("sub", "iam-admin", "roles", List.of("ADMIN"))))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            // The app `users` table is created by the example's db/migration at mount; this test
            // exercises the identity (tql_*) tables only.
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute(
                    "insert into tql_users (user_id, login_id, display_name, email, status) "
                            + "values ('u1','admin','Administrator','admin@example.com','ACTIVE')");
            statement.execute("insert into tql_users (user_id, login_id, display_name, status) "
                    + "values ('u2','bob','Bob','ACTIVE')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name) "
                    + "values ('r1','USER_READ','User Read')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
            statement.execute("insert into tql_groups (group_id, group_code, group_name) "
                    + "values ('g1','OPS','Operations')");
            statement.execute("insert into tql_user_groups (user_id, group_id) values ('u1','g1')");
            statement.execute("insert into tql_permissions "
                    + "(permission_id, permission_code, permission_name) "
                    + "values ('p1','users:read','Read users')");
            statement.execute("insert into tql_role_permissions (role_id, permission_id) "
                    + "values ('r1','p1')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-iam-admin-it");
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
