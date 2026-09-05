package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.identity.DefaultIdentityPack;
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
        runtime = TesseraqlRuntime.start(appHome, 0);
        io.tesseraql.security.session.SessionStore sessions = runtime.context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.SESSION_STORE_BEAN,
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

    /**
     * A path parameter is what the URL says, whatever else the request carries under the same
     * name. A query parameter used to reach the same message header and corrupt it — even
     * {@code ?id=u1} on {@code /users/u1} answered "not found", because both values arrived
     * joined — and a body field of that name replaced it outright.
     */
    @Test
    void aQueryParameterCannotDisplaceAPathParameter() throws Exception {
        assertThat(get("/_tesseraql/admin/users/u1?id=u2", true).body())
                .contains("Administrator").doesNotContain("User not found.");
        assertThat(get("/_tesseraql/admin/users/u1?id=u1", true).body())
                .contains("Administrator").doesNotContain("User not found.");
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

    /**
     * The directory is paged, not rendered whole.
     *
     * <p>It reads through an identity contract rather than through {@code sql:}, and the
     * framework applies pagination in the SQL step — one layer a contract source never passed
     * through. So every admin directory rendered its entire table, and this one's bulk form
     * posted one checkbox per row of {@code tql_users}: it broke at the transport's form-field
     * count long before anyone thought about the page.
     *
     * <p>Seeded past one page so the grid must actually cut, with the size declared small enough
     * that the fixture stays cheap.
     */
    @Test
    void theUserDirectoryIsPagedRatherThanRenderedWhole() throws Exception {
        seedManyUsers(60);
        try {
            String first = get("/_tesseraql/admin/users?size=5", true).body();

            // Five rows, not sixty: one checkbox per rendered row, and no more.
            assertThat(countOccurrences(first, "name=\"ids\"")).isEqualTo(5);
            assertThat(first).contains("hc-pagination").contains("Next");

            String second = get("/_tesseraql/admin/users?size=5&page=2", true).body();

            assertThat(countOccurrences(second, "name=\"ids\"")).isEqualTo(5);
            assertThat(second).contains("Page 2").contains("Prev");
            // A second page is a different page: the two do not overlap.
            assertThat(firstLoginOn(second)).isNotEqualTo(firstLoginOn(first));
        } finally {
            removeManyUsers();
        }
    }

    /** The filter still narrows, and a narrowed result that fits one page shows no pager. */
    @Test
    void pagingComposesWithTheFilter() throws Exception {
        seedManyUsers(60);
        try {
            String filtered = get("/_tesseraql/admin/users?q=bulk-000&size=5", true).body();

            assertThat(countOccurrences(filtered, "name=\"ids\"")).isEqualTo(1);
            assertThat(filtered).doesNotContain("hc-pagination");
        } finally {
            removeManyUsers();
        }
    }

    private static String firstLoginOn(String html) {
        java.util.regex.Matcher row = java.util.regex.Pattern
                .compile(">(bulk-\\d+)<").matcher(html);
        return row.find() ? row.group(1) : "";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle,
                at + needle.length())) {
            count++;
        }
        return count;
    }

    private static void seedManyUsers(int howMany) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (int i = 0; i < howMany; i++) {
                String id = String.format("bulk-%03d", i);
                statement.execute("insert into tql_users"
                        + " (user_id, login_id, display_name, status) values ('" + id + "','"
                        + id + "','" + id + "','ACTIVE')");
            }
        }
    }

    private static void removeManyUsers() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("delete from tql_users where user_id like 'bulk-%'");
        }
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
        io.tesseraql.security.session.SessionStore sessions = runtime.context()
                .lookup(
                        io.tesseraql.pipeline.TesseraqlProperties.SESSION_STORE_BEAN,
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
        io.tesseraql.security.session.SessionStore sessions = runtime.context()
                .lookup(
                        io.tesseraql.pipeline.TesseraqlProperties.SESSION_STORE_BEAN,
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
        io.tesseraql.security.session.SessionStore sessions = runtime.context()
                .lookup(
                        io.tesseraql.pipeline.TesseraqlProperties.SESSION_STORE_BEAN,
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

    /**
     * The condition surface (docs/access-governance.md slice 8): a role's network and hours
     * conditions are declared here, and a value the evaluator could never satisfy is refused
     * at the write rather than quietly closing the role to everybody.
     */
    @Test
    void aContextConditionIsDeclaredAndAMalformedOneIsRefused() throws Exception {
        assertThat(postForm("/_tesseraql/admin/roles/create",
                "code=cond.role&name=Conditioned&application=").statusCode()).isEqualTo(303);

        assertThat(postForm("/_tesseraql/admin/conditions/add",
                "roleCode=cond.role&conditionKind=network&value=172.16.0.0%2F12").statusCode())
                .isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/conditions/add",
                "roleCode=cond.role&conditionKind=hours&value=MON-FRI+09%3A00-18%3A00")
                .statusCode()).isEqualTo(303);

        String page = get("/_tesseraql/admin/conditions", true).body();
        assertThat(page).contains("cond.role").contains("172.16.0.0/12")
                .contains("MON-FRI 09:00-18:00");

        HttpResponse<String> refused = postForm("/_tesseraql/admin/conditions/add",
                "roleCode=cond.role&conditionKind=network&value=10.0.0.0%2F99");
        assertThat(refused.statusCode()).isNotEqualTo(303);
        assertThat(refused.body()).contains("TQL-IAM-4033");

        assertThat(postForm("/_tesseraql/admin/conditions/remove",
                "roleCode=cond.role&conditionKind=network&value=172.16.0.0%2F12").statusCode())
                .isEqualTo(303);
        assertThat(get("/_tesseraql/admin/conditions", true).body())
                .doesNotContain("172.16.0.0/12").contains("MON-FRI 09:00-18:00");
    }

    /**
     * The group surface (docs/access-governance.md slice 4): the schema was complete and
     * nothing wrote it, so this is the first path that creates one and puts somebody in it.
     */
    @Test
    void groupsAreCreatedEditedAndDeletedFromTheirOwnPages() throws Exception {
        assertThat(postForm("/_tesseraql/admin/groups/create", "code=FINANCE&name=Finance")
                .statusCode()).isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/roles/create",
                "code=finance.read&name=Read&application=").statusCode()).isEqualTo(303);

        assertThat(get("/_tesseraql/admin/groups", true).body())
                .contains("FINANCE").contains("/_tesseraql/admin/groups/FINANCE");

        assertThat(postForm("/_tesseraql/admin/groups/FINANCE/roles/grant",
                "roleCode=finance.read").statusCode()).isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/groups/FINANCE/members/add", "userId=u2")
                .statusCode()).isEqualTo(303);

        String detail = get("/_tesseraql/admin/groups/FINANCE", true).body();
        assertThat(detail).contains("finance.read").contains("bob");
        // The membership shows in the trail, like every other change to what bob holds.
        assertThat(get("/_tesseraql/admin/history?user=u2", true).body())
                .contains("group-joined").contains("FINANCE");

        assertThat(postForm("/_tesseraql/admin/groups/delete", "groupCode=FINANCE")
                .statusCode()).isEqualTo(303);
        assertThat(get("/_tesseraql/admin/groups", true).body()).doesNotContain("FINANCE");
        assertThat(get("/_tesseraql/admin/history?user=u2", true).body())
                .contains("group-left");
    }

    /**
     * Delegated administration end to end (docs/access-governance.md structural decision 7):
     * the route resolves its atom from the address, so a per-application grant is the thing
     * the gate checks — the link no unit test can prove, because it is the compiled endpoint,
     * the router's headers and the synthesized policy meeting on a live request.
     */
    @Test
    void aDelegatedAdministratorReachesOneApplicationAndWritesInsideIt() throws Exception {
        Session delegated = session("orders-admin",
                List.of("tql.iam.view.user-admin", "tql.iam.write.user-admin"));

        // The page the address names, checked against the atom the address resolves to.
        assertThat(get("/_tesseraql/admin/applications/user-admin", delegated).statusCode())
                .isEqualTo(200);
        // Another application's page is refused at the route, before any model runs — 403,
        // not the 404 an unknown member would answer a store-wide administrator.
        assertThat(get("/_tesseraql/admin/applications/billing", delegated).statusCode())
                .isEqualTo(403);

        HttpResponse<String> created = postForm(
                "/_tesseraql/admin/applications/user-admin/roles/create",
                "code=user-admin.approver&roleName=Approver", delegated);
        assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(303);
        assertThat(get("/_tesseraql/admin/applications/user-admin", delegated).body())
                .as("the role belongs to the application in the URL")
                .contains("user-admin.approver").contains("Approver");
        // The user is named by their login: an administrator confined to one application has
        // no store-wide user list to pick from.
        assertThat(postForm("/_tesseraql/admin/applications/user-admin/roles/assign",
                "loginId=bob&roleCode=user-admin.approver", delegated).statusCode())
                .isEqualTo(303);
        assertThat(get("/_tesseraql/admin/history?user=u2", true).body())
                .contains("user-admin.approver").contains("role-granted")
                .contains("orders-admin");

        assertThat(postForm("/_tesseraql/admin/applications/user-admin/permissions/grant",
                "loginId=bob&code=user-admin.approve", delegated).statusCode()).isEqualTo(303);
    }

    /** The three boundaries, refused on live requests rather than inferred from the scope. */
    @Test
    void containmentRefusesWhatTheDelegatedAtomDoesNotReach() throws Exception {
        Session delegated = session("orders-admin",
                List.of("tql.iam.view.user-admin", "tql.iam.write.user-admin"));

        // A stack-wide role belongs to the deployment, never to one application. USER_READ is
        // seeded with no application, so this is that boundary on a real row.
        HttpResponse<String> stackWide = postForm(
                "/_tesseraql/admin/applications/user-admin/roles/assign",
                "loginId=bob&roleCode=USER_READ", delegated);
        assertThat(stackWide.statusCode()).isNotEqualTo(303);
        assertThat(stackWide.body()).contains("TQL-IAM-4036").contains("stack-wide");

        // Delegating one application must not become a path to granting framework atoms.
        HttpResponse<String> atom = postForm(
                "/_tesseraql/admin/applications/user-admin/permissions/grant",
                "loginId=bob&code=tql.app.deploy.user-admin", delegated);
        assertThat(atom.statusCode()).isNotEqualTo(303);
        assertThat(atom.body()).contains("TQL-IAM-4036").contains("framework grant");

        // A code outside the application's own namespace is not this administrator's.
        HttpResponse<String> foreign = postForm(
                "/_tesseraql/admin/applications/user-admin/permissions/grant",
                "loginId=bob&code=billing.approve", delegated);
        assertThat(foreign.statusCode()).isNotEqualTo(303);
        assertThat(foreign.body()).contains("TQL-IAM-4036");
    }

    /**
     * The store-wide administrator passes the per-application gate by construction — the atom
     * policy ORs in the grant it narrows — and is still confined by the address they wrote
     * through, so a page addressed to one application cannot be used to reach another's role.
     */
    @Test
    void theStoreWideAdministratorPassesTheGateAndIsConfinedByTheAddress() throws Exception {
        assertThat(get("/_tesseraql/admin/applications/user-admin", true).statusCode())
                .isEqualTo(200);

        HttpResponse<String> crossApplication = postForm(
                "/_tesseraql/admin/applications/user-admin/roles/assign",
                "loginId=bob&roleCode=USER_READ");
        assertThat(crossApplication.statusCode()).isNotEqualTo(303);
        assertThat(crossApplication.body()).contains("TQL-IAM-4036");
    }

    /**
     * The applications list is the one page in the family with no application in its address,
     * so nothing resolves and the model narrows instead: a caller holding no IAM grant sees an
     * empty list rather than an open door (the stack shell's answer to the same shape).
     */
    @Test
    void theApplicationsListNarrowsToWhatTheCallerHolds() throws Exception {
        assertThat(get("/_tesseraql/admin/applications", true).body())
                .contains("/_tesseraql/admin/applications/user-admin");

        Session none = session("nobody", List.of());
        HttpResponse<String> empty = get("/_tesseraql/admin/applications", none);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body()).doesNotContain("/_tesseraql/admin/applications/user-admin");
        // And the page itself stays shut, since that one does have an atom to resolve.
        assertThat(get("/_tesseraql/admin/applications/user-admin", none).statusCode())
                .isEqualTo(403);
    }

    /** A browser session with its CSRF token, for a caller other than the store-wide admin. */
    private record Session(String cookie, String csrf) {
    }

    private static Session session(String login, List<String> permissions) {
        io.tesseraql.security.session.SessionStore sessions = runtime.context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.SESSION_STORE_BEAN,
                io.tesseraql.security.session.SessionStore.class);
        String sid = sessions.create(new io.tesseraql.security.Principal(login, login, login,
                null, List.of(), List.of(), permissions, Map.of()),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
        return new Session(sessions.cookieName() + "=" + sid,
                sessions.session(sid).csrfToken());
    }

    private static HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", session.cookie())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String form, Session session)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The access-review surface (docs/access-governance.md slice 5): open, decide, close —
     * and the close revoking through the ordinary write, so the trail names the campaign.
     */
    @Test
    void anAccessReviewSnapshotsDecidesAndAppliesOnClose() throws Exception {
        assertThat(postForm("/_tesseraql/admin/roles/create",
                "code=rev.temp&name=Temp&application=").statusCode()).isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/users/u2/roles/assign", "roleCode=rev.temp")
                .statusCode()).isEqualTo(303);

        assertThat(postForm("/_tesseraql/admin/reviews/open", "name=Q3+review&application=")
                .statusCode()).isEqualTo(303);
        String list = get("/_tesseraql/admin/reviews", true).body();
        assertThat(list).contains("Q3 review").contains("whole store");

        String prefix = "/_tesseraql/admin/reviews/";
        String reviewId = list.substring(list.indexOf(prefix + "rv-") + prefix.length());
        reviewId = reviewId.substring(0, reviewId.indexOf('"'));

        String detail = get("/_tesseraql/admin/reviews/" + reviewId, true).body();
        assertThat(detail).contains("rev.temp").contains("pending");

        assertThat(postForm("/_tesseraql/admin/reviews/" + reviewId + "/decide",
                "userId=u2&itemKind=role&subjectCode=rev.temp&decision=revoke&note=gone")
                .statusCode()).isEqualTo(303);
        assertThat(postForm("/_tesseraql/admin/reviews/" + reviewId + "/close", "")
                .statusCode()).isEqualTo(303);

        // The grant is gone, and the trail says which campaign decided it. Asserted on the
        // trail rather than on the user page, because that page's role dropdown lists every
        // role in the store — its absence there would prove nothing.
        assertThat(get("/_tesseraql/admin/history?user=u2", true).body())
                .contains("role-revoked").contains("rev.temp").contains(reviewId);
        assertThat(get("/_tesseraql/admin/reviews/" + reviewId, true).body())
                .contains("closed");
    }

    /**
     * The request surface (docs/access-governance.md slice 6): the approver queue is
     * filtered by ownership against the caller's own principal, and approving lands the
     * grant through the ordinary write.
     */
    @Test
    void anAccessRequestReachesItsOwnerAndApprovalLandsTheGrant() throws Exception {
        assertThat(postForm("/_tesseraql/admin/roles/create",
                "code=req.duty&name=Duty&application=").statusCode()).isEqualTo(303);

        // With no owner the role is not requestable, and the queue shows nothing.
        assertThat(get("/_tesseraql/admin/requests", true).body())
                .contains("No requests are waiting for you");

        assertThat(postForm("/_tesseraql/admin/requests/owners/add",
                "roleCode=req.duty&ownerKind=user&ownerRef=iam-admin").statusCode())
                .isEqualTo(303);
        assertThat(get("/_tesseraql/admin/requests", true).body())
                .contains("req.duty").contains("iam-admin");

        io.tesseraql.identity.IdentityService identity = new io.tesseraql.identity.IdentityService(
                name -> dataSource());
        io.tesseraql.identity.RealmConfig realm = io.tesseraql.identity.RealmConfig
                .managed("main", "main");
        io.tesseraql.identity.AccessRequests.request(identity, realm, "u2", "req.duty",
                "on call this week", null);

        String queue = get("/_tesseraql/admin/requests", true).body();
        assertThat(queue).contains("bob").contains("on call this week");
        String marker = "name=\"requestId\" value=\"";
        String requestId = queue.substring(queue.indexOf(marker) + marker.length());
        requestId = requestId.substring(0, requestId.indexOf('"'));

        assertThat(postForm("/_tesseraql/admin/requests/decide",
                "requestId=" + requestId + "&decision=approved&note=ok").statusCode())
                .isEqualTo(303);
        assertThat(get("/_tesseraql/admin/history?user=u2", true).body())
                .contains("req.duty").contains("request").contains(requestId);
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
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
        UserAdminAppJobs.parkDailyMaintenanceSchedule(target);
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

}
