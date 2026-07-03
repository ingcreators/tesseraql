package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.identity.DefaultIdentityPack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
 * Integration test for the Studio backend (design ch. 16): the explorer and source endpoints require
 * a bearer principal, and draft saves are accepted when Studio is not read-only.
 */
@Testcontainers
class StudioIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    // The bundled Studio UI (/_tesseraql/studio/ui/**) authenticates by browser session; the
    // hand-built JSON API (/_tesseraql/studio/*) still authenticates by bearer JWT. Tests of the UI
    // carry these session cookies; tests of the JSON API keep using token()/*WithToken bearers.
    static String adminCookie;
    static String adminCsrf;
    static String viewerCookie;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedIdentitySchema();
        seedScaffoldTable();
        establishSessions();
    }

    /** Creates browser sessions for an editor (ADMIN) and a read-only viewer, used by the UI tests. */
    private static void establishSessions() {
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext().getRegistry()
                .lookupByNameAndType(io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String adminSid = sessions.create(principal(List.of("ADMIN")));
        adminCookie = sessions.cookieName() + "=" + adminSid;
        adminCsrf = sessions.session(adminSid).csrfToken();
        viewerCookie = sessions.cookieName() + "=" + sessions.create(principal(List.of("VIEWER")));
    }

    private static io.tesseraql.security.Principal principal(List<String> roles) {
        return new io.tesseraql.security.Principal("studio-user", "studio-user", "Studio User",
                null, List.of(), roles, List.of(), Map.of());
    }

    /**
     * Clean tables the scaffold generator (backlog B3) introspects and generates a CRUD slice from.
     * They carry the conventions the generator recognises — a single auto-generated primary key, an
     * optimistic-locking {@code version} column, the canonical audit columns, and a single-column
     * unique index — and do not collide with the example app's own {@code web/users/**} routes.
     * {@code widgets} backs the read-only list/preview tests (never written), {@code gadgets} backs
     * the apply tests (written, so it must not be the table any preview test asserts is pristine).
     */
    private static void seedScaffoldTable() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.Statement statement = connection.createStatement()) {
            for (String table : new String[]{"widgets", "gadgets"}) {
                statement.execute("create table " + table + " ("
                        + "id bigserial primary key,"
                        + "name varchar(100) not null,"
                        + "quantity integer not null,"
                        + "active boolean not null default true,"
                        + "version bigint not null default 0,"
                        + "created_by varchar(64) not null,"
                        + "created_at timestamp not null,"
                        + "updated_by varchar(64) not null,"
                        + "updated_at timestamp not null)");
                statement.execute("create unique index uq_" + table + "_name on " + table
                        + " (name)");
            }
        }
    }

    /**
     * The framework IAM schema is not provisioned by this app's startup (it authenticates via bearer
     * JWT, never touching the identity store), so the contract test seeds it: the standard tql_*
     * tables plus one managed user for the {@code identity.list-users} contract to return.
     */
    private static void seedIdentitySchema() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.Statement statement = connection.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, status) "
                    + "values ('u1', 'admin', 'Administrator', 'ACTIVE')");
        }
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
    void explorerListsRoutesForAuthenticatedCaller() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/explorer", true);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode explorer = MAPPER.readTree(response.body());
        assertThat(explorer.get("readOnly").asBoolean()).isFalse();
        assertThat(explorer.get("routes")).anySatisfy(
                route -> assertThat(route.get("id").asText()).isEqualTo("users.search"));
    }

    @Test
    void explorerFiltersRoutesByQuery() throws Exception {
        int all = MAPPER.readTree(get("/_tesseraql/studio/explorer", true).body())
                .get("routes").size();
        JsonNode filtered = MAPPER.readTree(
                get("/_tesseraql/studio/explorer?q=" + enc("search"), true).body());

        JsonNode routes = filtered.get("routes");
        assertThat(routes.size()).isLessThan(all).isPositive();
        assertThat(routes).allSatisfy(route -> assertThat(
                (route.get("id").asText() + route.get("source").asText()).toLowerCase())
                .contains("search"));
    }

    @Test
    void uiExplorerRendersDirectoryTreeAndFilter() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // A filter box and a nested directory tree (folders as <details>, ids as leaf links).
        assertThat(response.body()).contains("id=\"explorer-filter\"")
                .contains("id=\"explorer-tree\"").contains("<details").contains("users.search");
    }

    @Test
    void uiExplorerFilterNarrowsTheRenderedTree() throws Exception {
        // The htmx filter re-renders the page server-side for q; the input echoes the query.
        HttpResponse<String> response = get("/_tesseraql/studio/ui?q=" + enc("search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("users.search").contains("value=\"search\"");
    }

    @Test
    void sourceReturnsFileContents() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/source?path=" + enc("web/api/users/search.sql"), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("content").asText()).contains("select");
    }

    @Test
    void draftSaveSucceedsWhenWritable() throws Exception {
        HttpResponse<String> response = post(
                "/_tesseraql/studio/drafts?path=" + enc("web/api/users/get.yml"), "edited", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("saved").asText())
                .isEqualTo("web/api/users/get.yml");
    }

    @Test
    void previewApplyAndReloadFlow() throws Exception {
        String path = "web/api/extra/get.yml";
        String newRoute = """
                version: tesseraql/v1
                id: extra.list
                kind: route
                recipe: query-json
                sql:
                  file: extra.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """;

        HttpResponse<String> preview = post(
                "/_tesseraql/studio/preview?path=" + enc(path), newRoute, true);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(preview.body()).get("valid").asBoolean()).isTrue();

        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(path), newRoute, true)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> apply = post("/_tesseraql/studio/apply?path=" + enc(path), "", true);
        assertThat(apply.statusCode()).isEqualTo(200);

        HttpResponse<String> reload = post("/_tesseraql/studio/reload", "", true);
        assertThat(reload.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(reload.body()).get("routes"))
                .anySatisfy(route -> assertThat(route.get("id").asText()).isEqualTo("extra.list"));
    }

    @Test
    void uiSourceWarnsWhenSourceChangedUnderAnEditedDraft() throws Exception {
        // A bound SQL file (not a route document, so it never joins the manifest) carries the draft.
        String path = "web/api/cedit/q.sql";
        Files.createDirectories(appHome.resolve("web/api/cedit"));
        Files.writeString(appHome.resolve(path), "select 1 as base_value\n");
        assertThat(
                post("/_tesseraql/studio/drafts?path=" + enc(path), "select 2 as drafted\n", true)
                        .statusCode())
                .isEqualTo(200);

        // Based on the current source: the editor shows the draft without a conflict warning.
        assertThat(get("/_tesseraql/studio/ui/source?path=" + enc(path), true).body())
                .doesNotContain("changed since this draft");

        // A concurrent change to the source surfaces the conflict warning and the force checkbox.
        Files.writeString(appHome.resolve(path), "select 3 as changed\n");
        assertThat(get("/_tesseraql/studio/ui/source?path=" + enc(path), true).body())
                .contains("changed since this draft").contains("name=\"force\"");
    }

    @Test
    void applyEndpointRejectsConcurrentConflictUnlessForced() throws Exception {
        String path = "web/api/capply/q.sql";
        Files.createDirectories(appHome.resolve("web/api/capply"));
        Files.writeString(appHome.resolve(path), "select 1 as base_value\n");
        assertThat(
                post("/_tesseraql/studio/drafts?path=" + enc(path), "select 2 as drafted\n", true)
                        .statusCode())
                .isEqualTo(200);

        // A concurrent change to the source under the draft.
        Files.writeString(appHome.resolve(path), "select 3 as changed\n");

        // Apply without force is a 409 conflict; the source is left untouched.
        assertThat(post("/_tesseraql/studio/apply?path=" + enc(path), "", true).statusCode())
                .isEqualTo(409);
        assertThat(Files.readString(appHome.resolve(path))).contains("changed");

        // force applies the draft over the changed source.
        assertThat(post("/_tesseraql/studio/apply?path=" + enc(path) + "&force=true", "", true)
                .statusCode()).isEqualTo(200);
        assertThat(Files.readString(appHome.resolve(path))).contains("drafted");
    }

    @Test
    void sqlEditorCrlfNoOpSaveShowsNoDiffAndApplyKeepsLf() throws Exception {
        // A bound .sql file (not a route document) carries the draft; the source is LF.
        String path = "web/api/crlf/q.sql";
        Files.createDirectories(appHome.resolve("web/api/crlf"));
        Files.writeString(appHome.resolve(path), "select 1\nselect 2\n");
        // Save a draft with CRLF newlines — what a browser textarea submits — but no real edits.
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(path), "select 1\r\nselect 2\r\n",
                true).statusCode()).isEqualTo(200);

        // The editor's compare panel shows no spurious changes: every line stays context.
        String editor = get("/_tesseraql/studio/ui/source?path=" + enc(path), true).body();
        assertThat(editor).doesNotContain("data-state=\"added\"")
                .doesNotContain("data-state=\"removed\"");

        // Applying the no-op draft leaves the source LF (no CRLF written back).
        assertThat(post("/_tesseraql/studio/apply?path=" + enc(path), "", true).statusCode())
                .isEqualTo(200);
        assertThat(Files.readString(appHome.resolve(path))).isEqualTo("select 1\nselect 2\n");
    }

    @Test
    void draftsOverviewListsPendingDraftsWithConflictStatus() throws Exception {
        String path = "web/api/dlist/q.sql";
        Files.createDirectories(appHome.resolve("web/api/dlist"));
        Files.writeString(appHome.resolve(path), "select 1\n");
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(path), "select 2\n", true)
                .statusCode()).isEqualTo(200);

        // The JSON overview includes the draft, not in conflict, as an edit (the source exists).
        assertThat(MAPPER.readTree(get("/_tesseraql/studio/drafts", true).body()))
                .anySatisfy(draft -> {
                    assertThat(draft.get("path").asText()).isEqualTo(path);
                    assertThat(draft.get("conflict").asBoolean()).isFalse();
                    assertThat(draft.get("isNew").asBoolean()).isFalse();
                });

        // A concurrent change to the source flags the draft as conflicting.
        Files.writeString(appHome.resolve(path), "select 3\n");
        assertThat(MAPPER.readTree(get("/_tesseraql/studio/drafts", true).body()))
                .anySatisfy(draft -> {
                    assertThat(draft.get("path").asText()).isEqualTo(path);
                    assertThat(draft.get("conflict").asBoolean()).isTrue();
                });

        // The overview page lists the draft (linked to its editor) in an hc-datagrid (table
        // consistency pass — same component as Audit / docs Routes); the explorer links to the page.
        assertThat(get("/_tesseraql/studio/ui/drafts", true).body()).contains(path)
                .contains("conflict").contains("/_tesseraql/studio/ui/source?path=")
                .contains("hc-datagrid");
        assertThat(get("/_tesseraql/studio/ui", true).body())
                .contains("/_tesseraql/studio/ui/drafts");
        // The page offers bulk apply/discard actions (Drafts differentiation vs the Explorer tree).
        assertThat(get("/_tesseraql/studio/ui/drafts", true).body())
                .contains("hx-post=\"/_tesseraql/studio/ui/drafts/apply-all\"")
                .contains("hx-post=\"/_tesseraql/studio/ui/drafts/discard-all\"")
                .contains("data-hc-confirm");
    }

    @Test
    void draftsBulkApplyAppliesCleanDraftsAndSkipsConflicts() throws Exception {
        // A clean draft (source unchanged) and a conflicting one (source changed underneath it).
        String clean = "web/api/bulkclean/q.sql";
        Files.createDirectories(appHome.resolve("web/api/bulkclean"));
        Files.writeString(appHome.resolve(clean), "select 1\n");
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(clean), "select 2 as done\n", true)
                .statusCode()).isEqualTo(200);

        String conflict = "web/api/bulkconflict/q.sql";
        Files.createDirectories(appHome.resolve("web/api/bulkconflict"));
        Files.writeString(appHome.resolve(conflict), "select 1\n");
        assertThat(
                post("/_tesseraql/studio/drafts?path=" + enc(conflict), "select 2 as edit\n", true)
                        .statusCode())
                .isEqualTo(200);
        Files.writeString(appHome.resolve(conflict), "select 9 as changed\n");

        // Bulk apply-all lands the clean draft and skips the conflicting one (source untouched).
        assertThat(postForm("/_tesseraql/studio/ui/drafts/apply-all", "").statusCode())
                .isEqualTo(303);
        assertThat(Files.readString(appHome.resolve(clean))).contains("done");
        assertThat(Files.readString(appHome.resolve(conflict))).contains("changed");
        // The clean draft is gone (applied); the conflicting one remains for review.
        String draftsJson = get("/_tesseraql/studio/drafts", true).body();
        assertThat(draftsJson).contains(conflict).doesNotContain(clean);
    }

    @Test
    void draftsBulkDiscardRemovesEveryDraft() throws Exception {
        String path = "web/api/bulkdiscard/q.sql";
        Files.createDirectories(appHome.resolve("web/api/bulkdiscard"));
        Files.writeString(appHome.resolve(path), "select 1\n");
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(path), "select 2\n", true)
                .statusCode()).isEqualTo(200);

        // Discard-all clears the whole pending set (the source file is left as-is).
        assertThat(postForm("/_tesseraql/studio/ui/drafts/discard-all", "").statusCode())
                .isEqualTo(303);
        assertThat(MAPPER.readTree(get("/_tesseraql/studio/drafts", true).body())).isEmpty();
        assertThat(Files.readString(appHome.resolve(path))).isEqualTo("select 1\n");
    }

    @Test
    void draftsOverviewRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/drafts", false).statusCode()).isEqualTo(401);
    }

    @Test
    void applyRecordsTheAuditTrailWithTheCaller() throws Exception {
        String path = "web/api/aud/q.sql";
        Files.createDirectories(appHome.resolve("web/api/aud"));
        Files.writeString(appHome.resolve(path), "select 1\n");
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(path), "select 2\n", true)
                .statusCode()).isEqualTo(200);
        assertThat(post("/_tesseraql/studio/apply?path=" + enc(path), "", true).statusCode())
                .isEqualTo(200);

        // The audit trail records the apply with the authenticated caller as the actor.
        assertThat(MAPPER.readTree(get("/_tesseraql/studio/audit", true).body()))
                .anySatisfy(entry -> {
                    assertThat(entry.get("action").asText()).isEqualTo("apply");
                    assertThat(entry.get("target").asText()).isEqualTo(path);
                    assertThat(entry.get("actor").asText()).isEqualTo("studio-user");
                    assertThat(entry.get("at").asText()).isNotBlank();
                });

        // The audit page renders the entry, with the filter chrome (H5), the total caption (I3), and
        // the sortable hc-datagrid headers (I2); the explorer links to it.
        assertThat(get("/_tesseraql/studio/ui/audit", true).body()).contains("Audit trail")
                .contains(path).contains("id=\"audit-filter\"").contains("id=\"audit-table\"")
                .contains("1 action").contains("class=\"hc-datagrid\"").contains("data-sortable")
                // default newest-first; the filter input keeps the sort across an htmx re-filter
                .contains("aria-sort=\"descending\"").contains("hx-vals=");
        assertThat(get("/_tesseraql/studio/ui", true).body())
                .contains("/_tesseraql/studio/ui/audit");

        // The filter searches the whole log (H5): a matching query keeps the entry, a miss drops it.
        assertThat(get("/_tesseraql/studio/ui/audit?q=" + enc("aud/q"), true).body())
                .contains(path);
        assertThat(get("/_tesseraql/studio/ui/audit?q=zzznomatch", true).body())
                .contains("No actions match").doesNotContain(path);
        // The page param is accepted and out-of-range pages are graceful (I3): page 2 of a 1-page
        // trail still renders 200 (an empty slice), it does not error.
        assertThat(get("/_tesseraql/studio/ui/audit?page=2", true).statusCode()).isEqualTo(200);
    }

    @Test
    void auditTrailRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/audit", false).statusCode()).isEqualTo(401);
    }

    @Test
    void editsRequireAnEditRoleWhenEditRolesAreConfigured() throws Exception {
        // The app sets editRoles: ADMIN; a caller without that role is read-only (backlog D6).
        String viewer = token(List.of("VIEWER"));

        // Every mutating endpoint is forbidden for the non-editor (403, not 401).
        assertThat(postWithToken("/_tesseraql/studio/drafts?path=" + enc("web/api/users/get.yml"),
                "x", viewer).statusCode()).isEqualTo(403);
        assertThat(postWithToken("/_tesseraql/studio/apply?path=" + enc("web/api/users/get.yml"),
                "", viewer).statusCode()).isEqualTo(403);
        assertThat(postWithToken("/_tesseraql/studio/scaffold/apply?table=gadgets", "", viewer)
                .statusCode()).isEqualTo(403);

        // Reads still work, and the explorer renders the read-only view — no edit chrome in the page
        // content (no New-route form). The Studio sidebar nav (track H1) lists every section
        // unconditionally for wayfinding; the editor-only pages render their own disabled state, so a
        // read-only viewer still cannot mutate (the POST endpoints 403 above).
        assertThat(getWithToken("/_tesseraql/studio/explorer", viewer).statusCode()).isEqualTo(200);
        // The UI landing is browser-auth: a read-only viewer reaches it via a VIEWER session.
        String ui = getWithCookie("/_tesseraql/studio/ui", viewerCookie).body();
        assertThat(ui).contains("read-only")
                // no create affordance for a viewer — the New-route drawer trigger is edit-gated
                .doesNotContain("hx-get=\"/_tesseraql/studio/ui/new")
                // the sidebar nav renders the Studio sections (Audit among them)
                .contains("/_tesseraql/studio/ui/audit");
    }

    @Test
    void editorRoleSeesTheEditChrome() throws Exception {
        // The configured edit role (ADMIN) keeps the full edit surface. The New-route drawer trigger
        // is the create chrome now (the form itself is the hc-drawer fragment it hx-gets).
        String ui = get("/_tesseraql/studio/ui", true).body();
        assertThat(ui).contains("editable")
                .contains("hx-get=\"/_tesseraql/studio/ui/new\"")
                .contains("/_tesseraql/studio/ui/audit");
    }

    @Test
    void studioPagesCarryTheStudioSidebarNav() throws Exception {
        // Track H1 + sidebar IA: Studio pages mount their own section nav in the shell sidebar (via
        // the studio-page fragment), not just the 3-app system nav — so every section is reachable
        // from anywhere, not only via the explorer header. The nav is grouped by job, each item
        // carries a sprite icon + an `.hc-shell__label` for the collapsible rail. Renders on a deep
        // page too.
        for (String path : new String[]{"/_tesseraql/studio/ui", "/_tesseraql/studio/ui/docs"}) {
            String body = get(path, true).body();
            assertThat(body).contains("hc-shell__sidebar")
                    // the kit's installNavCurrent marks the active link (data-hc-nav-current opt-in)
                    .contains("data-hc-nav-current")
                    // grouped by job — the section captions name each cluster
                    .contains(">Documentation<").contains(">Authoring<")
                    .contains(">Governance<").contains(">System<")
                    // the standalone landing + every section link (labels wrapped for the rail)
                    .contains(">Explorer<").contains(">Overview<").contains(">Coverage<")
                    .contains(">Schema<").contains(">Scaffold<").contains(">Migration<")
                    .contains(">SQL builder<").contains(">Drafts<").contains(">Audit<")
                    .contains(">Wizards<")
                    // the system apps stay reachable from Studio's sidebar
                    .contains(">Operations<").contains(">IAM Admin<")
                    // icons via the self-hosted sprite, and the collapse-rail + hamburger plumbing
                    .contains("/assets/_tesseraql/icons.svg#compass")
                    .contains("hc-shell__label").contains("data-collapsible")
                    .contains("hc-shell__toggle");
        }
    }

    @Test
    void explorerRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/explorer", false).statusCode()).isEqualTo(401);
    }

    @Test
    void previewValidatesPageTemplateWithSharedFragments() throws Exception {
        // The real page composes the framework tql/shell fragment and the shared app nav; the
        // preview engine resolves both, so the file validates as authored.
        String content = Files.readString(appHome.resolve("web/users/index.html"));
        HttpResponse<String> preview = post(
                "/_tesseraql/studio/preview?path=" + enc("web/users/index.html"), content, true);

        assertThat(preview.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(preview.body());
        assertThat(body.get("kind").asText()).isEqualTo("template");
        assertThat(body.get("valid").asBoolean()).isTrue();

        // Malformed markup is rejected through the same endpoint.
        HttpResponse<String> broken = post(
                "/_tesseraql/studio/preview?path=" + enc("web/users/index.html"),
                "<div th:text=\"${x}>oops</div>", true);
        assertThat(MAPPER.readTree(broken.body()).get("valid").asBoolean()).isFalse();
    }

    @Test
    void uiExplorerRendersHtmlPage() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        assertThat(response.body()).contains("TesseraQL Studio").contains("users.search");
    }

    @Test
    void uiSourceRendersHtmlPage() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/source?path=" + enc("web/api/users/search.sql"), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        // An existing on-disk file (no new-file draft) is labeled a served route (Studio sidebar IA).
        assertThat(response.body()).contains("web/api/users/search.sql").contains("select")
                .contains("served route");
    }

    @Test
    void uiSourceEditorCarriesTheLiveHighlightLanguage() throws Exception {
        // The editable hc-code field opts into hc's live syntax highlighting via data-lang (E);
        // .sql uses the consumer-registered 2-way SQL grammar (tql-sql), .yml a built-in grammar.
        assertThat(get("/_tesseraql/studio/ui/source?path=" + enc("web/api/users/search.sql"), true)
                .body()).contains("data-editable").contains("data-lang=\"tql-sql\"");
        assertThat(get("/_tesseraql/studio/ui/source?path=" + enc("web/api/users/get.yml"), true)
                .body()).contains("data-lang=\"yaml\"");
        // The bootstrap asset registers the tql-sql grammar (Studio backlog E slice 2).
        assertThat(get("/assets/_tesseraql/tesseraql.js", true).body())
                .contains("registerCodeLanguage(\"tql-sql\"");
    }

    @Test
    void studioSidebarIconSpriteIsServed() throws Exception {
        // The sidebar icons reference a self-hosted SVG sprite (no external CDN), served as a
        // framework asset from tesseraql/assets/icons.svg — the <use href> targets must resolve.
        HttpResponse<String> response = get("/assets/_tesseraql/icons.svg", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("image/svg+xml"));
        assertThat(response.body()).contains("id=\"compass\"").contains("id=\"scroll-text\"");
    }

    @Test
    void uiExplorerRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiDocsIndexRendersRoutesAndMigrationListing() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>")
                .contains("TesseraQL Docs")
                .contains("users.search")
                .contains("/_tesseraql/studio/ui/docs/route?id=users.search")
                .contains("Migrations")
                // The live-search input wires htmx to the search fragment, with a visible syntax hint
                // (H8) rather than burying the operators in the placeholder.
                .contains("hx-get=\"/_tesseraql/studio/ui/docs/search\"")
                .contains("<code>status:failing</code>").contains("<code>coverage:untested</code>");
    }

    @Test
    void uiDocsSearchReturnsRankedResultFragment() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/search?q=" + enc("users provision"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The provisioning route ranks first for both terms; the fragment links to its detail page,
        // and (H8) leads with a result count.
        assertThat(response.body()).contains("users.apiProvision")
                .contains("/_tesseraql/studio/ui/docs/route?id=users.apiProvision")
                .contains("matches</li>");
    }

    @Test
    void uiDocsRouteRendersTheRouteReference() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The live-fallback model renders the request surface, security, and bound SQL.
        assertThat(response.body()).contains("users.search").contains("/api/users")
                .contains("query-json").contains("Inputs").contains("search.sql");
        // The inferred data dependencies (SQL->table graph): users.search reads the `users` table.
        assertThat(response.body()).contains("Data dependencies").contains(">users<");
    }

    @Test
    void uiDocsRouteCarriesBreadcrumbAndOnThisPageNav() throws Exception {
        // Track H4: the 8-section route reference gets a breadcrumb (Docs > id) and an in-page jump
        // nav anchoring each present section, so a long page is navigable.
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("class=\"hc-breadcrumb\"").contains("aria-label=\"Breadcrumb\"")
                .contains("hc-breadcrumb__current").contains("aria-label=\"On this page\"")
                // the "On this page" nav is an hc-toc with scrollspy (the kit's installSpy)
                .contains("class=\"hc-toc\"").contains("data-hc-spy")
                .contains("class=\"hc-toc__link\" href=\"#sec-inputs\"")
                // jump links resolve to real section anchors
                .contains("href=\"#sec-inputs\"").contains("id=\"sec-inputs\"")
                .contains("href=\"#sec-sql\"").contains("id=\"sec-sql\"");
    }

    @Test
    void uiDocsTableCarriesBreadcrumbAndOnThisPageNav() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=" + enc("customers"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("class=\"hc-breadcrumb\"").contains(">Schema<")
                .contains("aria-label=\"On this page\"")
                .contains("href=\"#sec-columns\"").contains("id=\"sec-columns\"");
    }

    @Test
    void uiDocsRouteCrossLinksDataDependenciesToSchemaTables() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("deps.customers"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // `deps.customers` reads `customers`, which the schema.json overlay introspects, so the
        // data-dependency badge links to that table's page (the cross-link is resolved live).
        assertThat(response.body()).contains("Data dependencies")
                .contains("/_tesseraql/studio/ui/docs/schema/table?ds=main&amp;name=customers");
    }

    @Test
    void uiDocsRouteRendersNotFoundForUnknownId() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("no.such.route"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("No route").contains("no.such.route");
    }

    @Test
    void uiDocsIndexRendersTheRunOverlay() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The report.json overlay renders the run summary strip and per-route status columns.
        assertThat(response.body()).contains("Last test run")
                .contains("2/2 passed").contains("gate passed")
                .contains("covered").contains("lines");
    }

    @Test
    void uiDocsRouteCatalogIsSortable() throws Exception {
        // Platform-UX I2: the route catalog is an hc-datagrid with server-driven column sort — each
        // header is a link that re-requests sorted, and the server sets aria-sort the kit renders the
        // arrow from. No JS / no hx-vals='js:' — works under the strict CSP.
        String asc = get("/_tesseraql/studio/ui/docs?sort=id&dir=asc", true).body();
        assertThat(asc).contains("class=\"hc-datagrid\"").contains("data-sortable")
                // the active column reports ascending and its header link flips to descending
                .contains("aria-sort=\"ascending\"").contains("sort=id&amp;dir=desc");
        // ascending by id: deps.* sorts before users.*
        assertThat(asc.indexOf("deps.customers")).isLessThan(asc.indexOf("users.search"));

        String desc = get("/_tesseraql/studio/ui/docs?sort=id&dir=desc", true).body();
        assertThat(desc).contains("aria-sort=\"descending\"").contains("sort=id&amp;dir=asc");
        assertThat(desc.indexOf("users.search")).isLessThan(desc.indexOf("deps.customers"));
    }

    @Test
    void uiDocsRouteRendersTheRunOverlay() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The route page shows the run status card and the bound SQL's line/branch coverage.
        assertThat(response.body()).contains("Last test run").contains("covered")
                .contains("lines").contains("branches")
                // I4: the test-result detail moved from a title= tooltip (unreachable by SR/keyboard)
                // to hc-tooltip, so the page emits no title= attribute.
                .doesNotContain("title=\"");
        // Slice 4: the bound SQL renders line-by-line with per-line coverage classes.
        assertThat(response.body()).contains("hc-code")
                .contains("data-gutter=\"line-numbers\"").contains("data-state=\"covered\"")
                // the SQL is server-tokenized into hc-code token spans (hc 0.1.4)
                .contains("class=\"hc-code__tok\" data-tok=\"keyword\"");
    }

    @Test
    void uiDocsCoverageRendersTheDashboard() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/coverage", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("<!DOCTYPE html>")
                .contains("Coverage summary").contains("2/2 passed")
                .contains("gate passed").contains("Item coverage").contains("route");
        // Slice 5: the two-run history renders the trend sparklines.
        assertThat(response.body()).contains("Trend").contains("hc-sparkline")
                .contains("data-values");
        // F9: the trend shows its depth — the run count and the retained date span.
        assertThat(response.body()).contains("over 2 runs")
                .contains("2026-06-14").contains("2026-06-15");
    }

    @Test
    void uiDocsSearchFiltersByCoverageAndStatus() throws Exception {
        // coverage:covered keeps only the covered route from the overlay.
        HttpResponse<String> covered = get(
                "/_tesseraql/studio/ui/docs/search?q=" + enc("coverage:covered"), true);
        assertThat(covered.statusCode()).isEqualTo(200);
        assertThat(covered.body()).contains("id=users.search");

        // coverage:untested excludes the covered route and surfaces the rest.
        HttpResponse<String> untested = get(
                "/_tesseraql/studio/ui/docs/search?q=" + enc("coverage:untested"), true);
        assertThat(untested.body()).doesNotContain("id=users.search")
                .contains("id=users.apiProvision");

        // status:passing keeps only the route whose cases all passed.
        HttpResponse<String> passing = get(
                "/_tesseraql/studio/ui/docs/search?q=" + enc("status:passing"), true);
        assertThat(passing.body()).contains("id=users.search")
                .doesNotContain("id=users.apiProvision");
    }

    @Test
    void uiDocsCoverageRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/docs/coverage", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiDocsSchemaIndexRendersTheIntrospectedCatalog() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/schema", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The schema.json overlay (v3) renders each datasource's tables with detail links.
        assertThat(response.body()).startsWith("<!DOCTYPE html>")
                .contains("Datasource: main").contains("customers").contains("orders")
                .contains("schema/table?ds=main&amp;name=customers");
    }

    @Test
    void uiDocsSchemaTablesAreSortable() throws Exception {
        // Platform-UX I2: the schema table list is an hc-datagrid with server-driven column sort
        // (same CSP-clean pattern as the route catalog).
        String asc = get("/_tesseraql/studio/ui/docs/schema?sort=name&dir=asc", true).body();
        assertThat(asc).contains("class=\"hc-datagrid\"").contains("data-sortable")
                .contains("aria-sort=\"ascending\"").contains("sort=name&amp;dir=desc");
        // ascending by name: the customers row's link text precedes the orders row's
        assertThat(asc.indexOf(">customers<")).isLessThan(asc.indexOf(">orders<"));

        String desc = get("/_tesseraql/studio/ui/docs/schema?sort=name&dir=desc", true).body();
        assertThat(desc).contains("aria-sort=\"descending\"").contains("sort=name&amp;dir=asc");
        assertThat(desc.indexOf(">orders<")).isLessThan(desc.indexOf(">customers<"));
    }

    @Test
    void uiDocsSchemaTableRendersColumnsKeysAndForeignKeys() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=" + enc("orders"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The table page lists columns, the primary key, and the foreign key linked to its target.
        assertThat(response.body()).contains("Columns").contains("customer_id")
                .contains("Foreign keys").contains("schema/table?ds=main&amp;name=customers");
    }

    @Test
    void uiDocsSchemaTableCrossLinksTheRoutesThatUseIt() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=" + enc("customers"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The reverse SQL->table graph: the table page lists the routes that touch it, linking back
        // to each route page. `deps.customers` reads `customers` (see prepareAppHome).
        assertThat(response.body()).contains("Used by routes").contains("read by")
                .contains("/_tesseraql/studio/ui/docs/route?id=deps.customers");
    }

    @Test
    void uiDocsSchemaRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/docs/schema", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiDocsRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/docs", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiDocsExportPageListsTheDownloadableSpecs() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/export", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("<!DOCTYPE html>")
                .contains("Export API specs")
                .contains("openapi.json")
                .contains("/_tesseraql/studio/ui/docs/export/openapi")
                .contains("htmx-contract.json")
                .contains("/_tesseraql/studio/ui/docs/export/htmx");
    }

    @Test
    void uiDocsExportPageShowsTheApiChangelogAgainstTheBaseline() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/export", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The baseline sidecar drives the API spec diff: the legacy op is removed, current routes
        // (which the baseline lacks) are added — each added op links to its route page.
        assertThat(response.body()).contains("API changes").contains("removed")
                .contains("/api/legacy/widgets").contains("added")
                .contains("/_tesseraql/studio/ui/docs/route?id=users.search");
    }

    @Test
    void uiDocsExportOpenApiStreamsTheSpecAsADownload() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/export/openapi", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/json");
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment").contains("openapi.json");
        // The body is the real OpenAPI document, generated live from the manifest, byte-for-byte.
        JsonNode doc = MAPPER.readTree(response.body());
        assertThat(doc.path("openapi").asText()).isEqualTo("3.0.3");
        assertThat(doc.path("paths").has("/api/users")).isTrue();
    }

    @Test
    void uiDocsExportHtmxStreamsTheContractAsADownload() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/export/htmx", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/json");
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment").contains("htmx-contract.json");
        assertThat(MAPPER.readTree(response.body()).isObject()).isTrue();
    }

    @Test
    void uiDocsExportRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/docs/export", false).statusCode()).isEqualTo(401);
        assertThat(get("/_tesseraql/studio/ui/docs/export/openapi", false).statusCode())
                .isEqualTo(401);
    }

    @Test
    void uiDocsExportPageLinksToThePrintableCatalog() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/export", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Printable route catalog")
                .contains("/_tesseraql/studio/ui/docs/export/pdf");
    }

    @Test
    void uiDocsExportPdfRendersTheRouteCatalogAsADownloadablePdf() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/docs/export/pdf", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // tesseraql-pdf is on the test classpath, so the catalog renders to a real PDF data URL
        // shown in a preview frame with a download link, and the CSP allows the data: frame.
        assertThat(response.headers().firstValue("content-security-policy").orElse(""))
                .contains("frame-src 'self' data:");
        assertThat(response.body()).contains("Printable route catalog")
                .contains("src=\"data:application/pdf;base64,")
                .contains("download=\"routes.pdf\"");
    }

    @Test
    void uiDocsExportPdfRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/docs/export/pdf", false).statusCode()).isEqualTo(401);
    }

    // ---- F8 slice 3: opt-in signed share links (the IT config sets tesseraql.docs.share.secret) ---

    private static final java.util.regex.Pattern SHARE_URL = java.util.regex.Pattern.compile(
            "/_tesseraql/docs/share/route\\?id=users\\.search&amp;exp=(\\d+)&amp;sig=([A-Za-z0-9_-]+)");

    /** Pulls the minted (HTML-escaped) share link out of a route doc page and unescapes it. */
    private static String shareUrlFrom(String routePageHtml) {
        java.util.regex.Matcher matcher = SHARE_URL.matcher(routePageHtml);
        assertThat(matcher.find()).as("route page offers a share link").isTrue();
        return "/_tesseraql/docs/share/route?id=users.search&exp=" + matcher.group(1)
                + "&sig=" + matcher.group(2);
    }

    @Test
    void uiDocsRouteOffersASignedShareLinkWhenSharingIsEnabled() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Share")
                .contains("/_tesseraql/docs/share/route?id=users.search")
                // The share URL field carries a copy button via the kit's installCopy behavior.
                .contains("id=\"share-route\"").contains("data-hc-copy=\"#share-route\"");
        assertThat(shareUrlFrom(response.body())).isNotBlank();
    }

    @Test
    void docsShareRendersThePublicContractWithoutAuthentication() throws Exception {
        String shareUrl = shareUrlFrom(get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true).body());

        // Opened with NO bearer token: the signed link is the authorization.
        HttpResponse<String> shared = get(shareUrl, false);
        assertThat(shared.statusCode()).isEqualTo(200);
        assertThat(shared.body()).contains("shared").contains("GET").contains("/api/users")
                .contains("read-only view of this route");
        // The implementation is withheld from the public view: no SQL card, no column internals.
        assertThat(shared.body()).doesNotContain("<h3>SQL</h3>").doesNotContain("created_at");
    }

    @Test
    void docsShareRejectsATamperedOrMissingToken() throws Exception {
        // A forged signature renders the invalid-link notice, never the route (still no auth).
        HttpResponse<String> forged = get(
                "/_tesseraql/docs/share/route?id=users.search&exp=9999999999&sig=forged", false);
        assertThat(forged.statusCode()).isEqualTo(200);
        assertThat(forged.body()).contains("invalid or has expired").doesNotContain("/api/users");
        // No token at all is the same notice.
        assertThat(get("/_tesseraql/docs/share/route", false).body())
                .contains("invalid or has expired");
    }

    @Test
    void uiDocsTableOffersAShareLinkAndRendersItPublicly() throws Exception {
        HttpResponse<String> page = get(
                "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=" + enc("orders"), true);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("/_tesseraql/docs/share/table?ds=main");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "/_tesseraql/docs/share/table\\?ds=main&amp;name=orders&amp;exp=(\\d+)&amp;sig="
                        + "([A-Za-z0-9_-]+)")
                .matcher(page.body());
        assertThat(matcher.find()).isTrue();
        String shareUrl = "/_tesseraql/docs/share/table?ds=main&name=orders&exp=" + matcher.group(1)
                + "&sig=" + matcher.group(2);

        // Opened with NO bearer token: the signed link is the authorization.
        HttpResponse<String> shared = get(shareUrl, false);
        assertThat(shared.statusCode()).isEqualTo(200);
        assertThat(shared.body()).contains("shared").contains("orders").contains("customer_id");
        // A forged token renders the notice, never the table.
        assertThat(get("/_tesseraql/docs/share/table?ds=main&name=orders&exp=9999999999&sig=forged",
                false).body()).contains("invalid or has expired").doesNotContain("customer_id");
    }

    @Test
    void uiDocsCoverageOffersAShareLinkAndRendersItPublicly() throws Exception {
        HttpResponse<String> page = get("/_tesseraql/studio/ui/docs/coverage", true);
        assertThat(page.body()).contains("/_tesseraql/docs/share/coverage?exp=");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "/_tesseraql/docs/share/coverage\\?exp=(\\d+)&amp;sig=([A-Za-z0-9_-]+)")
                .matcher(page.body());
        assertThat(matcher.find()).isTrue();
        String shareUrl = "/_tesseraql/docs/share/coverage?exp=" + matcher.group(1)
                + "&sig=" + matcher.group(2);

        HttpResponse<String> shared = get(shareUrl, false);
        assertThat(shared.statusCode()).isEqualTo(200);
        assertThat(shared.body()).contains("shared").contains("Coverage summary")
                .contains("2/2 passed");
        // The public coverage view withholds the per-test failure list.
        assertThat(shared.body()).doesNotContain("Failing tests");
        assertThat(get("/_tesseraql/docs/share/coverage?exp=9999999999&sig=forged", false).body())
                .contains("invalid or has expired");
    }

    @Test
    void uiSaveAndApplyDraftViaForm() throws Exception {
        String path = "web/api/formtest/get.yml";
        String content = """
                version: tesseraql/v1
                id: formtest.list
                kind: route
                recipe: query-json
                sql:
                  file: formtest.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """;

        // post/redirect/get: saving redirects back to the source page with a status flag.
        HttpResponse<String> save = postForm("/_tesseraql/studio/ui/save",
                "path=" + enc(path) + "&content=" + enc(content));
        assertThat(save.statusCode()).isEqualTo(303);
        assertThat(save.headers().firstValue("location"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("/_tesseraql/studio/ui/source?path=").contains("saved=1"));

        HttpResponse<String> afterSave = get(save.headers().firstValue("location").orElseThrow(),
                true);
        assertThat(afterSave.statusCode()).isEqualTo(200);
        assertThat(afterSave.body()).contains("Draft saved.");
        // The multi-line content round-trips into the editor textarea.
        assertThat(afterSave.body()).contains("formtest.list").contains("query-json");

        // The applied route is reloaded, so its referenced SQL file must exist and compile.
        Files.createDirectories(appHome.resolve("web/api/formtest"));
        Files.writeString(appHome.resolve("web/api/formtest/formtest.sql"), "select 1");

        // Confirm-before-apply is on (IT config): applying without acknowledgment is rejected...
        HttpResponse<String> blocked = postForm("/_tesseraql/studio/ui/apply", "path=" + enc(path));
        assertThat(blocked.statusCode()).isEqualTo(422);
        assertThat(blocked.body()).contains("TQL-STUDIO-4223");
        // ...and applying with the diff acknowledgment succeeds.
        HttpResponse<String> apply = postForm("/_tesseraql/studio/ui/apply",
                "path=" + enc(path) + "&confirm=true");
        assertThat(apply.statusCode()).isEqualTo(303);
        HttpResponse<String> afterApply = get(apply.headers().firstValue("location").orElseThrow(),
                true);
        assertThat(afterApply.body()).contains("Draft applied and routes reloaded.");
    }

    @Test
    void uiPreviewReturnsValidationFragment() throws Exception {
        // A live edit validates without saving and comes back as an hc-alert fragment (not a page).
        HttpResponse<String> valid = postForm("/_tesseraql/studio/ui/preview",
                "path=" + enc("web/api/users/search.sql") + "&content=" + enc("select 1"));
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(valid.body()).doesNotContain("<!DOCTYPE")
                .contains("hc-alert").contains("data-variant=\"success\"");

        // A broken route surfaces inline as an error alert, again without touching the source.
        HttpResponse<String> invalid = postForm("/_tesseraql/studio/ui/preview",
                "path=" + enc("web/api/users/get.yml")
                        + "&content=" + enc("version: tesseraql/v1\nid: y\n"));
        assertThat(invalid.statusCode()).isEqualTo(200);
        assertThat(invalid.body()).contains("hc-alert").contains("data-variant=\"error\"");
    }

    @Test
    void renderEndpointRendersTemplateAgainstSampleModel() throws Exception {
        // The JSON API renders the on-disk template (no content override) against a sample model.
        String body = MAPPER.writeValueAsString(Map.of(
                "sampleModel", "users:\n  - id: 7\n    name: Renderbot\n    status: active\n"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/users/fragments/table/table.html"),
                body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        assertThat(render.get("kind").asText()).isEqualTo("html");
        assertThat(render.get("output").asText()).contains("Renderbot").contains("active")
                .doesNotContain("No matching users");
    }

    @Test
    void uiRenderReturnsRenderedFragmentWithIframePreview() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/render",
                "path=" + enc("web/users/fragments/table/table.html")
                        + "&sampleModel="
                        + enc("users:\n  - id: 1\n    name: Alice\n    status: active\n"));

        assertThat(response.statusCode()).isEqualTo(200);
        // A fragment (not a page) carrying the highlighted text output and the sandboxed iframe.
        assertThat(response.body()).doesNotContain("<!DOCTYPE html>")
                .contains("Rendered output").contains("Visual preview")
                .contains("class=\"tql-preview-frame\"").contains("srcdoc=")
                // the rendered HTML is server-tokenized for the text surface
                .contains("hc-code__tok")
                // the sample data flows into the render
                .contains("Alice");
    }

    @Test
    void renderEndpointRendersHtmlRouteAgainstExecutionContext() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("sampleModel",
                "sql:\n  rows:\n    - id: 1\n      name: Alice\n      status: active\n  rowCount: 1\n"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/users/fragments/table/get.yml"),
                body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        assertThat(render.get("kind").asText()).isEqualTo("html");
        assertThat(render.get("output").asText()).contains("Alice").contains("active");
    }

    @Test
    void renderEndpointRendersJsonRouteBody() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("sampleModel",
                "sql:\n  rows:\n    - id: 7\n      name: Sato\n  rowCount: 1\n"
                        + "params:\n  limit: 50\n  offset: 0\n"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/api/users/get.yml"), body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        assertThat(render.get("kind").asText()).isEqualTo("json");
        assertThat(render.get("output").asText()).contains("Sato").contains("\"count\" : 1");
    }

    @Test
    void renderEndpointMasksJsonOutputFields() throws Exception {
        // The users.search route masks created_at (mask: fixed); the preview shows the redacted
        // value, not the raw one (Studio backlog A1 follow-up).
        String body = MAPPER.writeValueAsString(Map.of("sampleModel",
                "sql:\n  rows:\n    - id: 7\n      name: Sato\n"
                        + "      created_at: 2026-06-18T00:00:00Z\n  rowCount: 1\n"
                        + "params:\n  limit: 50\n  offset: 0\n"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/api/users/get.yml"), body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        assertThat(render.get("output").asText()).contains("Sato").contains("[MASKED]")
                .doesNotContain("2026-06-18T00:00:00Z");
    }

    @Test
    void renderEndpointRendersExportRouteToPdf() throws Exception {
        // The print route (query-export, format: pdf) renders a real PDF preview through the
        // canonical codec — tesseraql-pdf is on the test classpath (Studio backlog A1 follow-up).
        String body = MAPPER.writeValueAsString(Map.of("sampleModel",
                "sql:\n  rows:\n    - name: Sato\n      status: ACTIVE\n"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/api/users/print/get.yml"), body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        assertThat(render.get("kind").asText()).isEqualTo("pdf");
        assertThat(render.get("output").asText()).startsWith("data:application/pdf;base64,");
    }

    @Test
    void uiSourceRouteOffersRenderedPreviewPanel() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("web/users/fragments/table/get.yml"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Rendered preview").contains("execution context")
                .contains("hx-post=\"/_tesseraql/studio/ui/render\"");
    }

    @Test
    void uiSourceAsyncActionsShowALoadingIndicator() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("web/users/fragments/table/get.yml"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // Every async action carries the loading affordance (the shared tql/shell::busy fragment,
        // now the kit's hc-spinner) so a slow DB call no longer reads as a hang: the indicator
        // renders and the submit button disables while the request is in flight.
        assertThat(response.body()).contains("htmx-indicator").contains("class=\"hc-spinner\"")
                .contains("role=\"status\"").contains("hx-disabled-elt=\"find button\"")
                // the live preview/render panels point an hx-indicator at their busy span
                .contains("hx-indicator=");
    }

    @Test
    void uiSourceToolPanelsAreCollapsibleDisclosures() throws Exception {
        // Track H3: the editor's secondary tools are uniform collapsible disclosures so the page is
        // no longer an overwhelming stack of open panels. Rendered preview stays open (primary
        // feedback); the on-demand tools collapse.
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("web/users/fragments/table/get.yml"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("<details class=\"hc-disclosure\" open")
                .contains("<summary>Rendered preview</summary>")
                .contains("<summary>Tests</summary>")
                // the bulky heading-stacked layout is gone — these are summaries now, not <h2>
                .doesNotContain("<h2>Rendered preview</h2>").doesNotContain("<h2>Tests</h2>");
    }

    @Test
    void uiSourceTemplateOffersRenderedPreviewPanel() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("web/users/fragments/table/table.html"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Rendered preview").contains("Sample data")
                // the panel wires htmx to the render endpoint
                .contains("hx-post=\"/_tesseraql/studio/ui/render\"");
        // The source page CSP admits the sandboxed preview iframe.
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("frame-src 'self'"));
    }

    @Test
    void runTestsEndpointRunsRouteSqlCasesAgainstDevDatasource() throws Exception {
        HttpResponse<String> response = post(
                "/_tesseraql/studio/runTests?path=" + enc("web/api/users/get.yml"), "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("ran").asBoolean()).isTrue();
        // The two sql cases targeting search.sql run against the seeded users table.
        assertThat(result.get("total").asInt()).isEqualTo(2);
        // The q=sato case passes against the seeded row; the runner reports a genuine result.
        assertThat(result.get("cases")).anySatisfy(testCase -> {
            assertThat(testCase.get("name").asText()).isEqualTo("search finds sato by name");
            assertThat(testCase.get("passed").asBoolean()).isTrue();
        });
    }

    @Test
    void runTestsReportsNoCasesForUncoveredRoute() throws Exception {
        // users.table binds search-table.sql, which no declarative case targets.
        HttpResponse<String> response = post("/_tesseraql/studio/runTests?path="
                + enc("web/users/fragments/table/get.yml"), "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("ran").asBoolean()).isFalse();
        assertThat(result.get("note").asText()).contains("No runnable test cases");
    }

    @Test
    void runTestsRunsValidateAndNotifyCasesForRoute() throws Exception {
        // apiProvision is covered by two validate cases and two notify cases (no sql cases): all
        // read-only, so the sandbox runs them against the seeded users table.
        HttpResponse<String> response = post("/_tesseraql/studio/runTests?path="
                + enc("web/api/users/provision/post.yml"), "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("ran").asBoolean()).isTrue();
        assertThat(result.get("total").asInt()).isEqualTo(4);
        assertThat(result.get("cases"))
                .anySatisfy(testCase -> assertThat(testCase.get("name").asText())
                        .contains("violates the userExists rule"));
        assertThat(result.get("cases"))
                .anySatisfy(testCase -> assertThat(testCase.get("name").asText())
                        .contains("notifies the confirmation mail"));
        assertThat(result.get("allPassed").asBoolean()).isTrue();
    }

    @Test
    void renderEndpointWithLiveDataRunsRouteSqlForRealRows() throws Exception {
        // No hand-authored sql.rows: live=true runs search.sql (q=sato) against the seeded DB.
        String body = MAPPER.writeValueAsString(Map.of(
                "sampleModel", "query:\n  q: sato\n  limit: 50\n  offset: 0\n",
                "live", "true"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/api/users/get.yml"), body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        assertThat(render.get("kind").asText()).isEqualTo("json");
        // Real rows from the seeded users table, not a hand-authored fixture.
        assertThat(render.get("output").asText()).contains("sato").contains("\"count\" : 1");
    }

    @Test
    void renderEndpointWithLiveDataRunsNamedQueriesToo() throws Exception {
        // A multi-binding route: live=true must run the main `sql` AND the named `query` `active`
        // through the sandbox, injecting both under their model keys (backlog category 3).
        String body = MAPPER.writeValueAsString(Map.of("sampleModel", "{}", "live", "true"));
        HttpResponse<String> response = post(
                "/_tesseraql/studio/render?path=" + enc("web/api/multi/get.yml"), body, true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode render = MAPPER.readTree(response.body());
        assertThat(render.get("ok").asBoolean()).isTrue();
        // Both bindings ran live: the main query's row and the named query's row are in the JSON.
        assertThat(render.get("output").asText()).contains("main-live").contains("query-live");
    }

    @Test
    void runTestsRunsJobHttpCallCase() throws Exception {
        // The directory-sync job is covered by one http-call case (pure: it plans the outbound step
        // and applies the egress allow-list without a network call).
        HttpResponse<String> response = post("/_tesseraql/studio/runTests?path="
                + enc("batch/directory-sync/job.yml"), "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("ran").asBoolean()).isTrue();
        assertThat(result.get("cases")).anySatisfy(testCase -> {
            assertThat(testCase.get("name").asText()).contains("allow-listed directory API");
            assertThat(testCase.get("passed").asBoolean()).isTrue();
        });
        assertThat(result.get("allPassed").asBoolean()).isTrue();
    }

    @Test
    void uiSourceJobOffersRunTestsWhenEnabled() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("batch/directory-sync/job.yml"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Run tests")
                .contains("hx-post=\"/_tesseraql/studio/ui/run-tests\"")
                // a job offers tests but not the (route/template-only) rendered-preview panel
                .doesNotContain("Use live data");
    }

    @Test
    void uiSourceRouteOffersRunTestsAndLiveDataWhenEnabled() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("web/api/users/get.yml"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Run tests")
                .contains("hx-post=\"/_tesseraql/studio/ui/run-tests\"")
                // the render panel offers the live-data toggle on a route page when enabled
                .contains("Use live data").contains("name=\"live\"");
    }

    @Test
    void scaffoldTablesListsIntrospectedTables() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/scaffold/tables", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode model = MAPPER.readTree(response.body());
        assertThat(model.get("enabled").asBoolean()).isTrue();
        assertThat(model.get("tables")).anySatisfy(table -> {
            assertThat(table.get("name").asText()).isEqualTo("widgets");
            assertThat(table.get("type").asText()).isEqualTo("TABLE");
            assertThat(table.get("scaffoldable").asBoolean()).isTrue();
            assertThat(table.get("primaryKey").asText()).isEqualTo("id");
        });
    }

    @Test
    void scaffoldTablesRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/scaffold/tables", false).statusCode()).isEqualTo(401);
    }

    @Test
    void scaffoldPreviewGeneratesCrudFilesForTable() throws Exception {
        HttpResponse<String> response = post(
                "/_tesseraql/studio/scaffold/preview?table=widgets", "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode model = MAPPER.readTree(response.body());
        assertThat(model.get("enabled").asBoolean()).isTrue();
        assertThat(model.get("table").asText()).isEqualTo("widgets");
        // A fresh table generates a full CRUD slice with no on-disk conflicts.
        assertThat(model.get("conflictCount").asInt()).isZero();
        assertThat(model.get("writeCount").asInt()).isEqualTo(model.get("total").asInt());
        assertThat(model.get("total").asInt()).isGreaterThan(1);
        assertThat(model.get("files")).anySatisfy(file -> {
            assertThat(file.get("path").asText()).isEqualTo("web/widgets/get.yml");
            assertThat(file.get("status").asText()).isEqualTo("new");
            assertThat(file.get("contentHtml").asText()).isNotBlank();
        });
    }

    @Test
    void uiScaffoldPageListsTables() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/scaffold", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Scaffold CRUD from a table").contains("widgets")
                .contains("hx-post=\"/_tesseraql/studio/ui/scaffold/preview\"")
                // table list uses hc-datagrid (table consistency pass)
                .contains("hc-datagrid")
                // Preview CRUD opens in a drawer (remote-dialog host), not a panel below
                .contains("data-hc-remote-dialog-root");
    }

    @Test
    void uiExplorerOffersScaffoldLinkWhenEnabled() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/_tesseraql/studio/ui/scaffold");
    }

    @Test
    void scaffoldApplyRequiresAuthentication() throws Exception {
        assertThat(post("/_tesseraql/studio/scaffold/apply?table=gadgets", "", false).statusCode())
                .isEqualTo(401);
    }

    @Test
    void scaffoldApplyWritesFilesIdempotentlyAndHonorsEditDetection() throws Exception {
        // 1. A fresh apply writes the full CRUD slice to disk; new route files need a restart.
        JsonNode first = applyGadgets(false);
        assertThat(first.get("blocked").asBoolean()).isFalse();
        assertThat(first.get("written")).anySatisfy(
                file -> assertThat(file.get("path").asText()).isEqualTo("web/gadgets/get.yml"));
        assertThat(first.get("needsRestart").asBoolean()).isTrue();
        assertThat(first.get("newRouteCount").asInt()).isGreaterThan(0);
        assertThat(Files.isRegularFile(appHome.resolve("web/gadgets/get.yml"))).isTrue();

        // 2. Re-applying is idempotent: nothing written, every file reported unchanged.
        JsonNode second = applyGadgets(false);
        assertThat(second.get("writtenCount").asInt()).isZero();
        assertThat(second.get("unchangedCount").asInt()).isGreaterThan(0);
        assertThat(second.get("blocked").asBoolean()).isFalse();

        // 3. A hand-edit (marker removed) is a conflict the apply skips, leaving the file untouched.
        Path edited = appHome.resolve("web/gadgets/get.yml");
        Files.writeString(edited, "hand-written, no scaffold marker\n");
        JsonNode third = applyGadgets(false);
        assertThat(third.get("blocked").asBoolean()).isTrue();
        assertThat(third.get("skipped"))
                .anySatisfy(path -> assertThat(path.asText()).isEqualTo("web/gadgets/get.yml"));
        assertThat(Files.readString(edited)).isEqualTo("hand-written, no scaffold marker\n");

        // 4. force overwrites the edited file with freshly generated, checksum-stamped content.
        JsonNode forced = applyGadgets(true);
        assertThat(forced.get("written")).anySatisfy(
                file -> assertThat(file.get("path").asText()).isEqualTo("web/gadgets/get.yml"));
        assertThat(Files.readString(edited)).contains("tesseraql-scaffold-checksum");
    }

    @Test
    void uiScaffoldPreviewOffersApplyForm() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/scaffold/preview",
                "table=widgets");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Create these files")
                .contains("hx-post=\"/_tesseraql/studio/ui/scaffold/apply\"")
                // the preview is a focused hc-drawer overlay, not an inline panel
                .contains("class=\"hc-drawer\"");
    }

    @Test
    void uiNewRouteCreatesStarterDraftAndRedirectsToEditor() throws Exception {
        String path = "web/api/newthing/get.yml";
        HttpResponse<String> created = postForm("/_tesseraql/studio/ui/new",
                "path=" + enc(path) + "&recipe=query-json");

        assertThat(created.statusCode()).isEqualTo(303);
        String location = created.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith("/_tesseraql/studio/ui/source?path=");

        // Following the redirect opens the source editor on the freshly saved starter draft, which the
        // editor labels a new (not-yet-served) route so the restart-to-serve consequence is clear.
        HttpResponse<String> editor = get(location, true);
        assertThat(editor.statusCode()).isEqualTo(200);
        assertThat(editor.body()).contains("api.newthing.get").contains("query-json")
                .contains("not served yet");

        // Studio sidebar IA: the new (not-yet-served) draft now shows in the Explorer tree as its own
        // pending node, so it is visible before a restart serves it (no more "created it, where did
        // it go?"). The folder appears only because of the draft; "unserved draft" marks the leaf.
        assertThat(get("/_tesseraql/studio/ui", true).body())
                .contains("newthing").contains("unserved draft");
    }

    @Test
    void uiExplorerOffersNewRouteFormWhenWritable() throws Exception {
        // The Explorer offers a "New route" drawer trigger + the remote-dialog host; the form itself
        // is the hc-drawer fragment served by the trigger's hx-get (Studio sidebar IA).
        HttpResponse<String> response = get("/_tesseraql/studio/ui", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("New route")
                .contains("hx-get=\"/_tesseraql/studio/ui/new\"")
                .contains("data-hc-remote-dialog-root");

        HttpResponse<String> drawer = get("/_tesseraql/studio/ui/new", true);
        assertThat(drawer.statusCode()).isEqualTo(200);
        assertThat(drawer.body()).contains("class=\"hc-drawer\"")
                .contains("action=\"/_tesseraql/studio/ui/new\"").contains("Create route");
    }

    @Test
    void uiExplorerFolderOffersContextualNewRoute() throws Exception {
        // Studio sidebar IA: each folder offers a "New route here" drawer trigger; the fragment it
        // hx-gets seeds the New-route Path (?prefix=) so creation inherits the browsed location.
        assertThat(get("/_tesseraql/studio/ui", true).body()).contains("New route here")
                .contains("hx-get=\"/_tesseraql/studio/ui/new?prefix=");
        HttpResponse<String> drawer = get(
                "/_tesseraql/studio/ui/new?prefix=" + enc("web/api/users/"), true);
        assertThat(drawer.statusCode()).isEqualTo(200);
        assertThat(drawer.body()).contains("class=\"hc-drawer\"")
                .contains("value=\"web/api/users/\"");
    }

    private static JsonNode applyGadgets(boolean force) throws Exception {
        HttpResponse<String> response = post(
                "/_tesseraql/studio/scaffold/apply?table=gadgets&force=" + force, "", true);
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    @Test
    void sandboxDataSourceRollsBackWritesAndCapsRows() throws Exception {
        try (com.zaxxer.hikari.HikariDataSource raw = rawDataSource()) {
            // A probe table created via a normal (auto-commit) connection persists.
            try (java.sql.Connection setup = raw.getConnection();
                    java.sql.Statement ddl = setup.createStatement()) {
                ddl.execute("drop table if exists sandbox_probe");
                ddl.execute("create table sandbox_probe (id int)");
            }
            javax.sql.DataSource sandbox = new SandboxDataSource(raw, 5, 2);
            try (java.sql.Connection conn = sandbox.getConnection()) {
                // A write executes inside the sandbox transaction and is visible within it...
                try (java.sql.PreparedStatement write = conn.prepareStatement(
                        "insert into sandbox_probe values (1)")) {
                    assertThat(write.executeUpdate()).isEqualTo(1);
                }
                try (java.sql.PreparedStatement read = conn.prepareStatement(
                        "select count(*) from sandbox_probe");
                        java.sql.ResultSet rs = read.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                // Row cap: a 100-row query yields at most maxRows (2).
                try (java.sql.PreparedStatement query = conn.prepareStatement(
                        "select g from generate_series(1, 100) g");
                        java.sql.ResultSet rs = query.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        count++;
                    }
                    assertThat(count).isEqualTo(2);
                }
            }
            // ...but on close it rolled back: the probe table is empty again.
            try (java.sql.Connection check = raw.getConnection();
                    java.sql.PreparedStatement count = check.prepareStatement(
                            "select count(*) from sandbox_probe");
                    java.sql.ResultSet rs = count.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (java.sql.Connection cleanup = raw.getConnection();
                    java.sql.Statement ddl = cleanup.createStatement()) {
                ddl.execute("drop table sandbox_probe");
            }
        }
    }

    private static com.zaxxer.hikari.HikariDataSource rawDataSource() {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(2);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    @Test
    void runTestsRunsWriteCaseAndRollsItBack() throws Exception {
        // The probe route binds a write SQL (update … returning); its sql test case runs through the
        // writable sandbox, sees the returned row, and is rolled back so nothing persists.
        HttpResponse<String> response = post("/_tesseraql/studio/runTests?path="
                + enc("web/api/probe/post.yml"), "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("ran").asBoolean()).isTrue();
        assertThat(result.get("allPassed").asBoolean()).isTrue();
        assertThat(result.get("cases")).anySatisfy(testCase -> {
            assertThat(testCase.get("name").asText()).contains("probe update returns the affected");
            assertThat(testCase.get("passed").asBoolean()).isTrue();
        });

        // The write rolled back: sato is still ACTIVE, never persisted as PROBED.
        try (com.zaxxer.hikari.HikariDataSource raw = rawDataSource();
                java.sql.Connection check = raw.getConnection();
                java.sql.PreparedStatement status = check.prepareStatement(
                        "select status from users where name = 'sato'");
                java.sql.ResultSet rs = status.executeQuery()) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void runTestsRunsContractCaseThroughSandboxedIdentity() throws Exception {
        // The admin list route binds the identity.list-users contract; the injected contract case
        // runs through the sandboxed identity service against the framework identity store.
        HttpResponse<String> response = post("/_tesseraql/studio/runTests?path="
                + enc("web/admin/users/get.yml"), "", true);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(response.body());
        assertThat(result.get("ran").asBoolean()).isTrue();
        assertThat(result.get("allPassed").asBoolean()).isTrue();
        assertThat(result.get("cases")).anySatisfy(testCase -> {
            assertThat(testCase.get("name").asText()).contains("list-users contract");
            assertThat(testCase.get("passed").asBoolean()).isTrue();
        });
    }

    @Test
    void uiSourceShowsDraftBadgeAndDiscardClearsIt() throws Exception {
        String path = "web/api/users/search.sql";

        // Saving a draft makes the source page flag it and offer a comparison and a discard.
        assertThat(postForm("/_tesseraql/studio/ui/save",
                "path=" + enc(path) + "&content=" + enc("select 1 -- draft")).statusCode())
                .isEqualTo(303);
        assertThat(get("/_tesseraql/studio/ui/source?path=" + enc(path), true).body())
                .contains("unsaved draft")
                .contains("Compare against saved source")
                // the compare panel renders a real unified diff (draft differs from the source)
                .contains("data-mode=\"diff\"")
                // the diff lines are server-tokenized (the SQL draft has a SELECT keyword)
                .contains("class=\"hc-code__tok\" data-tok=\"keyword\"")
                .contains("Discard draft");

        // Discarding redirects back and the badge is gone (the source is restored in the editor).
        HttpResponse<String> discard = postForm("/_tesseraql/studio/ui/discard",
                "path=" + enc(path));
        assertThat(discard.statusCode()).isEqualTo(303);
        assertThat(discard.headers().firstValue("location"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("/_tesseraql/studio/ui/source?path="));
        assertThat(get("/_tesseraql/studio/ui/source?path=" + enc(path), true).body())
                .doesNotContain("unsaved draft");
    }

    @Test
    void wizardIndexAndFormRender() throws Exception {
        HttpResponse<String> index = get("/_tesseraql/studio/ui/wizard", true);
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).contains("Setup wizards").contains("SAML SP")
                // H7: the index orients the user — what each wizard does and to start with the realm.
                .contains("Set up an").contains("Federate logins with a SAML");

        HttpResponse<String> form = get("/_tesseraql/studio/ui/wizard/saml", true);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("SAML SP wizard").contains("name=\"acsUrl\"")
                // H7: jargony fields carry inline help instead of a bare label + cryptic placeholder.
                .contains("register this exact URL with the IdP")
                .contains("the example OID is");
    }

    @Test
    void wizardSubmitDownloadsGeneratedConfig() throws Exception {
        String form = "spAudience=" + enc("https://app.example.com/saml")
                + "&acsUrl=" + enc("https://app.example.com/acs")
                + "&ssoUrl=" + enc("https://idp.example.com/sso")
                + "&loginIdAttribute=uid&provision=true"
                + "&publicKeyPath=" + enc("security/saml/idp-signing.pem");

        HttpResponse<String> result = postForm("/_tesseraql/studio/ui/wizard/saml", form);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/yaml"));
        assertThat(result.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"tesseraql-saml.yml\"");
        assertThat(result.body()).contains("audience: \"https://app.example.com/saml\"");
        assertThat(result.body()).contains("ssoUrl: \"https://idp.example.com/sso\"");
        assertThat(result.body()).contains("publicKey: \"security/saml/idp-signing.pem\"");
        assertThat(result.body()).contains("provision: true");
        // Optional fields left blank are omitted.
        assertThat(result.body()).doesNotContain("sloUrl");
    }

    @Test
    void oidcWizardRendersAndGeneratesConfig() throws Exception {
        HttpResponse<String> index = get("/_tesseraql/studio/ui/wizard", true);
        assertThat(index.body()).contains("OIDC provider");

        HttpResponse<String> form = get("/_tesseraql/studio/ui/wizard/oidc", true);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("OIDC provider wizard").contains("name=\"discoveryUri\"");

        String body = "discoveryUri="
                + enc("https://idp.example.com/.well-known/openid-configuration")
                + "&clientId=my-app"
                + "&redirectUri=" + enc("https://app.example.com/_tesseraql/oidc/callback")
                + "&scopes=" + enc("openid profile email")
                + "&postLoginUrl=" + enc("/") + "&provision=true";
        HttpResponse<String> result = postForm("/_tesseraql/studio/ui/wizard/oidc", body);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"tesseraql-oidc.yml\"");
        assertThat(result.body())
                .contains("clientId: \"my-app\"")
                .contains("discoveryUri: "
                        + "\"https://idp.example.com/.well-known/openid-configuration\"")
                .contains("scopes: \"openid profile email\"")
                .contains("provision: true");
    }

    @Test
    void wizardSubmitRejectsMissingRequiredField() throws Exception {
        HttpResponse<String> result = postForm("/_tesseraql/studio/ui/wizard/saml",
                "acsUrl=" + enc("https://app.example.com/acs"));

        assertThat(result.statusCode()).isEqualTo(400);
    }

    @Test
    void wizardRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/wizard", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiMigrationPageRendersTheCreateForm() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/migration", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The editable caller sees the V/R create form (Studio backlog: migration authoring).
        assertThat(response.body()).contains("New migration").contains("Versioned")
                .contains("Repeatable").contains("name=\"description\"");
    }

    @Test
    void uiMigrationCreatesAVersionedFileAndLinksToTheEditor() throws Exception {
        String form = "kind=versioned&datasource=main&description=" + enc("studio it migration")
                + "&ddl=" + enc("create table it_widgets (id bigint primary key);");
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/migration", form);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Migration created").contains("db/migration/")
                .contains("studio_it_migration.sql").contains("Open in editor");
        // The migration was written under db/migration with the DDL, auto-numbered V<n> (numerically).
        Path migration;
        try (Stream<Path> files = Files.list(appHome.resolve("db/migration"))) {
            migration = files
                    .filter(p -> p.getFileName().toString().endsWith("studio_it_migration.sql"))
                    .findFirst().orElseThrow();
        }
        assertThat(migration.getFileName().toString()).matches("V\\d+__studio_it_migration\\.sql");
        assertThat(Files.readString(migration)).contains("create table it_widgets");
    }

    @Test
    void uiMenuPageRendersTheEditorAndFallbackNote() throws Exception {
        // With no config/menu.yml the editor shows the add form and the templates/nav.html fallback.
        Files.deleteIfExists(appHome.resolve("config/menu.yml"));
        HttpResponse<String> response = get("/_tesseraql/studio/ui/menu", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Sidebar menu").contains("Add menu item")
                .contains("templates/nav.html");
    }

    @Test
    void uiMenuAddWritesConfigRendersRowAndRecordsAudit() throws Exception {
        Files.deleteIfExists(appHome.resolve("config/menu.yml"));
        String form = "label=" + enc("Reports") + "&href=" + enc("/reports")
                + "&icon=activity&roles=" + enc("ADMIN, STAFF");
        assertThat(postForm("/_tesseraql/studio/ui/menu/add", form).statusCode()).isEqualTo(303);

        String menu = Files.readString(appHome.resolve("config/menu.yml"));
        assertThat(menu).contains("Reports").contains("/reports").contains("ADMIN")
                .contains("STAFF");
        // The editor now renders the row with its label, href, and role-based visibility.
        String page = get("/_tesseraql/studio/ui/menu", true).body();
        assertThat(page).contains("Reports").contains("/reports").contains("Roles: ADMIN, STAFF");
        // The write is recorded to the audit trail (backlog D6).
        assertThat(Files.readString(appHome.resolve("work/studio/audit/audit.jsonl")))
                .contains("\"action\":\"menu\"").contains("config/menu.yml");
    }

    @Test
    void uiMenuMoveReordersThenRemoveDeletes() throws Exception {
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/menu.yml"), """
                menu:
                  - {label: First, href: /first}
                  - {label: Second, href: /second}
                """);

        // Move the second item up → it precedes the first on disk.
        assertThat(postForm("/_tesseraql/studio/ui/menu/move", "index=1&dir=up").statusCode())
                .isEqualTo(303);
        String moved = Files.readString(appHome.resolve("config/menu.yml"));
        assertThat(moved.indexOf("Second")).isLessThan(moved.indexOf("First"));

        // Remove index 0 (now "Second") → only "First" remains.
        assertThat(postForm("/_tesseraql/studio/ui/menu/remove", "index=0").statusCode())
                .isEqualTo(303);
        String removed = Files.readString(appHome.resolve("config/menu.yml"));
        assertThat(removed).contains("First").doesNotContain("Second");
    }

    @Test
    void uiMenuPreviewFiltersByRoleServerSide() throws Exception {
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/menu.yml"), """
                menu:
                  - {label: Home, href: /home}
                  - {label: Admin, href: /admin, roles: [ADMIN]}
                """);

        // A non-matching role sees only the public item; the gated href is not emitted.
        String anon = get("/_tesseraql/studio/ui/menu/preview?role=" + enc("NOBODY"), true).body();
        assertThat(anon).contains("/home").doesNotContain("/admin");
        // The ADMIN role sees both.
        String admin = get("/_tesseraql/studio/ui/menu/preview?role=ADMIN", true).body();
        assertThat(admin).contains("/home").contains("/admin");
    }

    @Test
    void uiMenuEditorSuggestsRoutesRolesIconsAndFlagsDanglingHrefs() throws Exception {
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/menu.yml"), """
                menu:
                  - {label: Users, href: /users}
                  - {label: Ghost, href: /definitely-not-a-route}
                """);
        String page = get("/_tesseraql/studio/ui/menu", true).body();

        // Href autocomplete lists served route paths; role autocomplete comes from the app's policies.
        assertThat(page).contains("id=\"menu-href-options\"").contains("value=\"/users\"");
        assertThat(page).contains("id=\"menu-role-options\"").contains("value=\"USER_WRITE\"");
        // The icon picker lists the self-hosted sprite ids.
        assertThat(page).contains("id=\"menu-icon-options\"").contains("value=\"panel-left\"");
        // An href that matches no served route is flagged (a non-blocking hint).
        assertThat(page).contains("unmatched");
    }

    @Test
    void uiMenuEditorIsReadOnlyForAViewer() throws Exception {
        assertThat(getWithCookie("/_tesseraql/studio/ui/menu", viewerCookie).body())
                .contains("do not have permission");
    }

    @Test
    void uiMenuRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/menu", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiHealthDashboardListsLintFindings() throws Exception {
        // A parseable route with an unknown recipe is a lint error (TQL-YAML-1002) but does not break
        // the running app (nothing recompiles it); removed in the finally so the suite stays clean.
        String bad = "web/api/lintprobe/get.yml";
        Files.createDirectories(appHome.resolve("web/api/lintprobe"));
        Files.writeString(appHome.resolve(bad), """
                version: tesseraql/v1
                id: lint.probe
                kind: route
                recipe: not-a-real-recipe
                response:
                  json:
                    body:
                      ok: true
                """);
        try {
            HttpResponse<String> response = get("/_tesseraql/studio/ui/health", true);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Health").contains("TQL-YAML-1002")
                    .contains("web/api/lintprobe/get.yml");
        } finally {
            Files.deleteIfExists(appHome.resolve(bad));
            Files.deleteIfExists(appHome.resolve("web/api/lintprobe"));
        }
    }

    @Test
    void uiHealthDashboardRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/health", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiSecurityOverviewMapsRoutesToPoliciesAndFlagsUnprotected() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/security", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The route→policy map lists a secured route with its policy, and the policy catalogue shows
        // the policy's rule summary (users.read grants a USER_READ role).
        assertThat(response.body()).contains("Security").contains("/admin/users")
                .contains("users.read").contains("USER_READ");
        // /users is a public page, so an unprotected route is flagged.
        assertThat(response.body()).contains("no auth");
        // The policy → routes reverse link surfaces how many routes each policy guards.
        assertThat(response.body()).contains("route(s)").contains("unused-policy");
    }

    @Test
    void uiSecurityOverviewRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/security", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiMessagesEditorRendersTheCatalogAcrossLocales() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/messages", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The user-admin app ships en + ja catalogs sharing keys like users.list.title.
        assertThat(response.body()).contains("Messages").contains("users.list.title")
                .contains("Set a translation");
    }

    @Test
    void uiMessagesSetUpsertsATranslationAndRecordsAudit() throws Exception {
        String key = "studio.it.greeting";
        String form = "locale=en&key=" + enc(key) + "&value=" + enc("Hello from IT");
        assertThat(postForm("/_tesseraql/studio/ui/messages/set", form).statusCode())
                .isEqualTo(303);

        // The nested key was written into messages/en.yml, and the write was audited.
        String catalog = Files.readString(appHome.resolve("messages/en.yml"));
        assertThat(catalog).contains("greeting").contains("Hello from IT");
        assertThat(Files.readString(appHome.resolve("work/studio/audit/audit.jsonl")))
                .contains("\"action\":\"message\"");
        // The editor now lists the new key.
        assertThat(get("/_tesseraql/studio/ui/messages", true).body()).contains(key);
    }

    @Test
    void uiMessagesEditorRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/messages", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiConfigViewerRendersEffectiveConfigAndRedactsSecrets() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/config", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // A known flattened key is shown; a secret's literal value (docs.share.secret) is redacted.
        assertThat(response.body()).contains("Configuration")
                .contains("tesseraql.studio.editRoles").contains("redacted")
                .doesNotContain("it-docs-share-secret");
    }

    @Test
    void uiConfigViewerRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/config", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiConfigEditorOverridesAWhitelistedSettingInOverlay() throws Exception {
        Path overlay = appHome.resolve("config/overlay.yml");
        try {
            assertThat(postForm("/_tesseraql/studio/ui/config/set",
                    "key=" + enc("tesseraql.app.name") + "&value=StudioITApp").statusCode())
                    .isEqualTo(303);
            // Written to overlay.yml (base untouched) and audited.
            assertThat(Files.readString(overlay)).contains("StudioITApp");
            assertThat(Files.readString(appHome.resolve("work/studio/audit/audit.jsonl")))
                    .contains("\"action\":\"config\"");
            // The effective config (base + overlay) reflects the override.
            assertThat(get("/_tesseraql/studio/ui/config", true).body()).contains("StudioITApp");
        } finally {
            Files.deleteIfExists(overlay);
        }
    }

    @Test
    void uiDataBrowserListsRowsOfATable() throws Exception {
        // The identity schema seeds one tql_users row (login_id 'admin').
        HttpResponse<String> response = get("/_tesseraql/studio/ui/data?table=tql_users", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Data browser").contains("login_id").contains("admin");
    }

    @Test
    void uiDataBrowserFiltersAndSortsByColumn() throws Exception {
        // Filter tql_users to the seeded 'admin' login and sort by it — validated columns, bound value.
        HttpResponse<String> response = get("/_tesseraql/studio/ui/data?table=tql_users"
                + "&filterColumn=login_id&filterValue=admin&sort=login_id&dir=desc", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("admin");
        // A non-matching filter yields no rows.
        assertThat(get("/_tesseraql/studio/ui/data?table=tql_users"
                + "&filterColumn=login_id&filterValue=" + enc("nobody-xyz"), true).body())
                .contains("No rows");
    }

    @Test
    void uiDocsTableLinksToTheDataBrowser() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=customers", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Browse data")
                .contains("/_tesseraql/studio/ui/data?table=");
    }

    @Test
    void uiDataBrowserExportsTheViewAsCsv() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/data/export?table=tql_users", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/csv");
        // RFC-4180 CSV: a header row + the seeded admin row.
        assertThat(response.body()).contains("login_id").contains("admin").contains("\r\n");
    }

    @Test
    void uiDataBrowserExportRespectsAFilter() throws Exception {
        // Filtering to a non-matching value exports only the header (no data rows).
        HttpResponse<String> response = get("/_tesseraql/studio/ui/data/export?table=tql_users"
                + "&filterColumn=login_id&filterValue=" + enc("nobody-xyz"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("login_id").doesNotContain("admin");
    }

    @Test
    void uiDataBrowserRejectsAnUnknownTable() throws Exception {
        // A table not in the live catalog is rejected (guards against SQL injection via the name).
        HttpResponse<String> response = get("/_tesseraql/studio/ui/data?table="
                + enc("nope; drop table tql_users"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("No such table");
    }

    @Test
    void uiDataBrowserRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/data", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiValidationBuilderRendersTheForm() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/validation-builder", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Validation builder").contains("name=\"operation\"");
    }

    @Test
    void uiValidationBuilderGeneratesARuleSnippet() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/validation-builder/build",
                "operation=min&source=body&field=age&value=18&id=ageAtLeast18");

        assertThat(response.statusCode()).isEqualTo(200);
        // The generated snippet is HTML-escaped in the fragment (>= becomes &gt;=).
        assertThat(response.body()).contains("validate:").contains("body.age &gt;= 18")
                .contains("field: age");
    }

    @Test
    void uiFlagsEditorSetsAndRemovesAFlag() throws Exception {
        Path flags = appHome.resolve("config/flags.yml");
        try {
            assertThat(postForm("/_tesseraql/studio/ui/flags/set",
                    "name=betaBanner&type=boolean&value=true").statusCode()).isEqualTo(303);
            // Written to config/flags.yml and audited; the editor lists it.
            assertThat(Files.readString(flags)).contains("betaBanner").contains("true");
            assertThat(Files.readString(appHome.resolve("work/studio/audit/audit.jsonl")))
                    .contains("\"action\":\"flag\"");
            assertThat(get("/_tesseraql/studio/ui/flags", true).body()).contains("betaBanner");

            // Remove it.
            assertThat(
                    postForm("/_tesseraql/studio/ui/flags/remove", "name=betaBanner").statusCode())
                    .isEqualTo(303);
            assertThat(Files.readString(flags)).doesNotContain("betaBanner");
        } finally {
            Files.deleteIfExists(flags);
        }
    }

    @Test
    void uiFlagsEditorTogglesABooleanFlag() throws Exception {
        Path flags = appHome.resolve("config/flags.yml");
        try {
            Files.writeString(flags, "flags:\n  beta: false\n");
            // The flag renders an "off" toggle whose form posts the opposite value (true).
            String page = get("/_tesseraql/studio/ui/flags", true).body();
            assertThat(page).contains(">off<").contains("value=\"true\"");

            // Clicking the toggle (post the opposite) flips it on; the page now shows "on".
            assertThat(postForm("/_tesseraql/studio/ui/flags/set",
                    "name=beta&type=boolean&value=true").statusCode()).isEqualTo(303);
            assertThat(Files.readString(flags)).contains("beta: true");
            assertThat(get("/_tesseraql/studio/ui/flags", true).body()).contains(">on<")
                    .contains("value=\"false\"");
        } finally {
            Files.deleteIfExists(flags);
        }
    }

    @Test
    void uiFlagsEditorRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/flags", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiConfigEditorRejectsANonWhitelistedKey() throws Exception {
        Path overlay = appHome.resolve("config/overlay.yml");
        try {
            // A key outside the curated whitelist is rejected — nothing is written.
            assertThat(postForm("/_tesseraql/studio/ui/config/set",
                    "key=" + enc("tesseraql.datasources.main.password") + "&value=pwned")
                    .statusCode()).isNotEqualTo(303);
            assertThat(!Files.exists(overlay) || !Files.readString(overlay).contains("pwned"))
                    .isTrue();
        } finally {
            Files.deleteIfExists(overlay);
        }
    }

    @Test
    void uiSecurityPolicyEditWritesOverlayAndRebindsTheEngineLive() throws Exception {
        String policyId = "studio.it.editpolicy";
        Path overlay = appHome.resolve("config/overlay.yml");
        io.tesseraql.security.Principal editor = principal(List.of("STUDIO_EDITOR"));
        try {
            // The edit form is offered to an editor.
            assertThat(get("/_tesseraql/studio/ui/security", true).body())
                    .contains("Grant a role or permission");

            // Grant STUDIO_EDITOR a brand-new policy — creates it in the overlay.
            assertThat(postForm("/_tesseraql/studio/ui/security/policy/add-rule",
                    "policy=" + enc(policyId) + "&kind=role&value=STUDIO_EDITOR").statusCode())
                    .isEqualTo(303);
            // Written to config/overlay.yml (the base config is untouched) and audited.
            assertThat(Files.readString(overlay)).contains(policyId).contains("STUDIO_EDITOR");
            assertThat(Files.readString(appHome.resolve("work/studio/audit/audit.jsonl")))
                    .contains("\"action\":\"policy\"");
            // The change is authorized LIVE: the rebound PolicyEngine now permits it.
            assertThat(currentPolicyEngine().permits(policyId, editor)).isTrue();

            // Revoke it → the policy now grants no one, live.
            assertThat(postForm("/_tesseraql/studio/ui/security/policy/remove-rule",
                    "policy=" + enc(policyId) + "&kind=role&value=STUDIO_EDITOR").statusCode())
                    .isEqualTo(303);
            assertThat(currentPolicyEngine().permits(policyId, editor)).isFalse();
        } finally {
            Files.deleteIfExists(overlay);
        }
    }

    private static io.tesseraql.security.policy.PolicyEngine currentPolicyEngine() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                io.tesseraql.camel.TesseraqlProperties.POLICY_ENGINE_BEAN,
                io.tesseraql.security.policy.PolicyEngine.class);
    }

    @Test
    void uiTryConsoleRendersTheFormWithRoutePathSuggestions() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/try", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("API console").contains("id=\"try-path-options\"")
                .contains("value=\"/users\"");
    }

    @Test
    void uiTryRunLoopbackInvokesAPublicRouteAndShowsTheResponse() throws Exception {
        // /users is a public page; the console proxies a loopback GET and renders status + body.
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/try/run",
                "method=GET&path=" + enc("/users"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("200").contains("Users");
    }

    @Test
    void uiTryRunRejectsANonAppPath() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/try/run",
                "method=GET&path=" + enc("http://example.com/"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("app path beginning with");
    }

    @Test
    void uiTryConsolePrefillsMethodPathAndBodyFromADeepLink() throws Exception {
        // Deep-linked to a POST route with a declared input → path + a JSON body skeleton are filled.
        HttpResponse<String> response = get("/_tesseraql/studio/ui/try?path="
                + enc("/api/users/provision") + "&method=POST", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("value=\"/api/users/provision\"").contains("userName");
    }

    @Test
    void uiDocsRouteLinksToTheApiConsole() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=users.apiProvision", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Try in API console")
                .contains("/_tesseraql/studio/ui/try?path=");
    }

    @Test
    void uiTryRunForwardsTheSessionForBrowserAuthedRoutes() throws Exception {
        // The Health page is browser-authenticated: a loopback with no session is rejected (401),
        // but forwarding the caller's session (useSession) authenticates it (200).
        String target = enc("/_tesseraql/studio/ui/health");
        assertThat(postForm("/_tesseraql/studio/ui/try/run", "method=GET&path=" + target).body())
                .contains("401");
        assertThat(postForm("/_tesseraql/studio/ui/try/run",
                "method=GET&path=" + target + "&useSession=true").body()).contains("200");
    }

    @Test
    void uiSourceMigrationOffersDryRunWhenEnabled() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("db/migration/V1__create_users.sql"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // A migration file's editor offers the sandbox dry-run action (migration authoring slice 2).
        assertThat(response.body()).contains("Dry-run").contains("/_tesseraql/studio/ui/dry-run");
    }

    @Test
    void uiDryRunAppliesValidDdlInTheSandboxAndRollsItBack() throws Exception {
        // The DDL is the posted (live editor) content, not the file; it runs and rolls back.
        String form = "path=" + enc("db/migration/V1__create_users.sql") + "&content="
                + enc("create table dryrun_probe (id int primary key);");
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/dry-run", form);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Applies cleanly");
        // It rolled back — re-running the same create succeeds again (the table never persisted).
        assertThat(postForm("/_tesseraql/studio/ui/dry-run", form).body())
                .contains("Applies cleanly");
    }

    @Test
    void uiDryRunReportsBrokenDdlAsAnError() throws Exception {
        String form = "path=" + enc("db/migration/V1__create_users.sql") + "&content="
                + enc("create tabl dryrun_bad (id int);");
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/dry-run", form);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("Applies cleanly")
                .contains("data-variant=\"error\"");
    }

    @Test
    void uiMigrationPageOffersTheDdlBuilder() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/migration", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The form-driven DDL builder (migration authoring slice 3).
        assertThat(response.body()).contains("DDL builder").contains("Add column")
                .contains("Create index");
    }

    @Test
    void uiMigrationBuildGeneratesAddColumnDdl() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/migration/build",
                "operation=add-column&table=users&column=nickname&type=text&notNull=true");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("ALTER TABLE users ADD COLUMN nickname text NOT NULL;");
    }

    @Test
    void uiMigrationBuildGeneratesCreateIndexDdl() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/migration/build",
                "operation=create-index&table=users&columns=" + enc("name, status")
                        + "&unique=true");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("CREATE UNIQUE INDEX users_name_status_idx ON users (name, status);");
    }

    @Test
    void uiMigrationBuildGeneratesCreateTableDdl() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/migration/build",
                "operation=create-table&table=widgets&primaryKey=id&columnLines="
                        + enc("id bigint\nname text not null"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("CREATE TABLE widgets (").contains("id bigint,")
                .contains("name text not null,").contains("PRIMARY KEY (id)");
    }

    @Test
    void uiSqlBuilderPageRendersTheTableDropdownAndOperations() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/sql-builder", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The 2-way SQL builder offers the introspected tables and the DML operations.
        assertThat(response.body()).contains("2-way SQL builder").contains(">customers<")
                .contains("select by primary key").contains("insert")
                // H8: the intro describes the actual bind style (a bind name + sql.params), not the
                // stale "/* params.id */ … values from body" copy from before #153/#154.
                .contains("<code>/* id */ 0</code>").contains("-- sql.params")
                .doesNotContain("/* params.id */");
    }

    @Test
    void uiSqlBuilderGeneratesTwoWaySqlForATable() throws Exception {
        HttpResponse<String> select = postForm("/_tesseraql/studio/ui/sql-builder/build",
                "table=customers&operation=select-by-pk");
        assertThat(select.statusCode()).isEqualTo(200);
        // The bind references the param NAME (id), with the sql.params mapping documented, and a
        // typed dummy literal (id is bigint -> 0).
        assertThat(select.body()).contains("select id, email").contains("from customers")
                .contains("where id = /* id */ 0;").contains("--   id: params.id");

        // Insert skips the identity column (customers.id) and binds values from the body.
        HttpResponse<String> insert = postForm("/_tesseraql/studio/ui/sql-builder/build",
                "table=customers&operation=insert");
        assertThat(insert.body()).contains("insert into customers (email)").contains("/* email */")
                .contains("--   email: params.email");
    }

    @Test
    void uiSqlBuilderFiltersByAChosenColumnViaTheCascade() throws Exception {
        // The column cascade returns the table's columns as select options.
        HttpResponse<String> columns = get(
                "/_tesseraql/studio/ui/sql-builder/columns?table=" + enc("customers"), true);
        assertThat(columns.statusCode()).isEqualTo(200);
        assertThat(columns.body()).contains(">id<").contains(">email<");

        // select-by-column filters on the chosen column with a params bind.
        HttpResponse<String> select = postForm("/_tesseraql/studio/ui/sql-builder/build",
                "table=customers&operation=select-by-column&column=email");
        assertThat(select.body()).contains("from customers").contains("where email = /* email */");
    }

    @Test
    void uiSourceRouteSqlOffersTheInEditorSqlBuilder() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/source?path="
                + enc("web/api/users/search.sql"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // A route .sql editor offers the SQL builder, which appends the snippet into the editor
        // textarea (hx-target the editor, beforeend), with the table dropdown from the schema overlay.
        assertThat(response.body()).contains("SQL builder")
                .contains("hx-target=\"#source-editor\"").contains("hx-swap=\"beforeend\"")
                .contains(">customers<");
    }

    @Test
    void uiSqlBuilderGeneratesInListAndOptionalIfFilters() throws Exception {
        // IN-list: the directive is followed by a parenthesized typed dummy.
        HttpResponse<String> inList = postForm("/_tesseraql/studio/ui/sql-builder/build",
                "table=customers&operation=select-by-column-in&column=email");
        assertThat(inList.body()).contains("where email in /* email */ (");

        // Optional: the predicate is wrapped in a /*%if … */ … /*%end*/ directive.
        HttpResponse<String> optional = postForm("/_tesseraql/studio/ui/sql-builder/build",
                "table=customers&operation=select-by-column-optional&column=email");
        assertThat(optional.body()).contains("/*%if email != null */")
                .contains("and email = /* email */").contains("/*%end*/");
    }

    @Test
    void uiMigrationPageOffersSchemaDiffWhenABaselineIsPresent() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/migration", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("From schema changes").contains("Generate from diff");
    }

    @Test
    void uiMigrationDiffGeneratesAMigrationFromTheSchemaBaseline() throws Exception {
        HttpResponse<String> response = postForm("/_tesseraql/studio/ui/migration/diff", "");

        assertThat(response.statusCode()).isEqualTo(200);
        // The baseline lacks customers.email and the orders table, so the diff adds both.
        assertThat(response.body()).contains("ALTER TABLE customers ADD COLUMN email")
                .contains("CREATE TABLE orders (");
    }

    @Test
    void uiMigrationBuilderTablesComeFromTheSchemaOverlay() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui/migration", true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The DDL builder's table input is a dropdown populated from the introspected schema overlay
        // (the IT's schema.json holds customers + orders).
        assertThat(response.body()).contains("<select").contains(">customers<")
                .contains(">orders<");
    }

    @Test
    void uiMigrationColumnsCascadeReturnsTheTablesColumns() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/migration/columns?table=" + enc("customers"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The cascade returns the chosen table's columns as datalist options (customers: id, email).
        assertThat(response.body()).contains("value=\"id\"").contains("value=\"email\"");
    }

    // The UI form posts go to /_tesseraql/studio/ui/** (browser auth): carry the admin session
    // cookie and its CSRF token. The bearer is harmless on these routes (browser auth ignores it).
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

    // Shared helpers carry BOTH a bearer (for the JSON API) and the admin session cookie + CSRF (for
    // the /ui pages), so each route authenticates with whichever it requires.
    private static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (auth) {
            request.header("Authorization", "Bearer " + token()).header("Cookie", adminCookie);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body, boolean auth)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth) {
            request.header("Authorization", "Bearer " + token()).header("Cookie", adminCookie)
                    .header("X-CSRF-Token", adminCsrf);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A GET carrying a specific browser-session cookie (for the role-scoped /ui tests). */
    private static HttpResponse<String> getWithCookie(String path, String cookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", cookie).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> getWithToken(String path, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + bearer).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postWithToken(String path, String body, String bearer)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        return token(List.of("ADMIN"));
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(Map.of(
                "sub", "studio-user", "preferred_username", "studio-user", "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-studio-it");
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

                tesseraql:
                  studio:
                    enabled: true
                    readOnly: false
                    editRoles: ADMIN
                    confirmApply: true
                    testRunner:
                      enabled: true
                    scaffold:
                      enabled: true
                    dataBrowser:
                      enabled: true
                  docs:
                    share:
                      secret: it-docs-share-secret-0123456789
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // A run overlay in the reserved namespace exercises the portal's report-layer rendering
        // (documentation portal v2). Hand-authored to the report.json shape the report goal emits.
        Files.createDirectories(target.resolve(".tesseraql/docs"));
        Files.writeString(target.resolve(".tesseraql/docs/report.json"), """
                {
                  "schemaVersion": 1, "runId": "it-run", "generatedAt": "2026-06-15T12:00:00Z",
                  "summary": { "total": 2, "passed": 2, "failed": 0, "sqlLineRatio": 0.8,
                               "sqlBranchRatio": 1.0, "gatePassed": true },
                  "thresholds": { "sqlLine": 0.0, "sqlBranch": 0.0, "kinds": {} },
                  "gate": { "passed": true, "failures": [] },
                  "kinds": [ { "kind": "route", "ratio": 1.0, "covered": 1, "declared": 1,
                               "uncovered": [] } ],
                  "routes": {
                    "users.search": { "covered": true,
                      "tests": [ { "name": "search finds sato by name", "passed": true,
                                   "message": "OK" } ],
                      "sql": [ { "file": "web/api/users/search.sql", "lineRatio": 0.8,
                                 "branchRatio": 1.0, "branchCount": 1, "branchOutcomes": 2,
                                 "coveredLines": [1, 2], "coverableLines": [1, 2, 3] } ],
                      "itemCoverage": {} }
                  }
                }
                """);
        // A two-run history exercises the dashboard's coverage-trend sparklines.
        Files.writeString(target.resolve(".tesseraql/docs/history.json"), """
                [ { "runId": "it-run-0", "generatedAt": "2026-06-14T12:00:00Z", "total": 2,
                    "passed": 1, "failed": 1, "sqlLineRatio": 0.5, "sqlBranchRatio": 0.5,
                    "gatePassed": false },
                  { "runId": "it-run", "generatedAt": "2026-06-15T12:00:00Z", "total": 2,
                    "passed": 2, "failed": 0, "sqlLineRatio": 0.8, "sqlBranchRatio": 1.0,
                    "gatePassed": true } ]
                """);
        // A schema overlay exercises the portal's schema-layer rendering (documentation portal v3).
        // Hand-authored to the schema.json shape the schema goal emits.
        Files.writeString(target.resolve(".tesseraql/docs/schema.json"), """
                { "schemaVersion": 1, "generatedAt": "2026-06-15T12:00:00Z",
                  "datasources": { "main": { "tables": [
                    { "name": "customers", "type": "TABLE", "schema": "public",
                      "columns": [
                        { "name": "id", "jdbcType": -5, "sqlTypeName": "bigserial", "size": 19,
                          "nullable": false, "autoincrement": true, "defaultValue": null },
                        { "name": "email", "jdbcType": 12, "sqlTypeName": "varchar", "size": 320,
                          "nullable": false, "autoincrement": false, "defaultValue": null } ],
                      "primaryKey": ["id"], "foreignKeys": [],
                      "uniqueIndexes": [ { "name": "customers_email_key", "columns": ["email"],
                                          "unique": true } ] },
                    { "name": "orders", "type": "TABLE", "schema": "public",
                      "columns": [
                        { "name": "id", "jdbcType": -5, "sqlTypeName": "bigserial", "size": 19,
                          "nullable": false, "autoincrement": true, "defaultValue": null },
                        { "name": "customer_id", "jdbcType": -5, "sqlTypeName": "bigint",
                          "size": 19, "nullable": false, "autoincrement": false,
                          "defaultValue": null } ],
                      "primaryKey": ["id"],
                      "foreignKeys": [ { "name": "orders_customer_id_fkey",
                                         "columns": ["customer_id"], "refTable": "customers",
                                         "refColumns": ["id"] } ],
                      "uniqueIndexes": [] }
                  ] } } }
                """);
        // A schema baseline (customers without email, and no orders table) so the migration page's
        // schema-diff generates the migration capturing those additions (migration authoring).
        Files.writeString(target.resolve(".tesseraql/docs/schema.baseline.json"), """
                { "schemaVersion": 1, "generatedAt": "2026-06-15T12:00:00Z",
                  "datasources": { "main": { "tables": [
                    { "name": "customers", "type": "TABLE", "schema": "public", "columns": [
                        { "name": "id", "jdbcType": -5, "sqlTypeName": "bigserial", "size": 19,
                          "nullable": false, "autoincrement": true, "defaultValue": null } ],
                      "primaryKey": ["id"], "foreignKeys": [], "uniqueIndexes": [] } ] } } }
                """);
        // An OpenAPI baseline sidecar so the export page renders the API changelog (spec diff): it
        // names a legacy operation the current app no longer has (-> removed) while the current
        // routes the baseline lacks show as added.
        Files.writeString(target.resolve(".tesseraql/docs/openapi.baseline.json"), """
                { "openapi": "3.0.3", "info": { "title": "user-admin", "version": "1.0.0" },
                  "paths": { "/api/legacy/widgets": { "get": {
                      "operationId": "legacy.widgets",
                      "responses": { "200": { "description": "OK" } } } } } }
                """);
        // A write route plus a sql test case targeting its write SQL, to exercise the writable
        // sandbox (A2 write/command): the case runs an `update … returning` and is rolled back.
        Files.createDirectories(target.resolve("web/api/probe"));
        Files.writeString(target.resolve("web/api/probe/post.yml"), """
                version: tesseraql/v1
                id: probe.update
                kind: route
                recipe: command-json

                input:
                  name:
                    type: string
                    required: true

                security:
                  auth: bearer
                  policy: users.write

                sql:
                  file: probe.sql
                  mode: update
                  params:
                    name: body.name

                response:
                  json:
                    status: 200
                    body:
                      affected: sql.affectedRows
                """);
        Files.writeString(target.resolve("web/api/probe/probe.sql"),
                "update users set status = 'PROBED' where name = /* name */ 'sato'\n"
                        + "returning id, name, status;\n");
        Files.writeString(target.resolve("tests/studio-write-test.yml"), """
                tests:
                  - name: the probe update returns the affected row
                    sql:
                      file: web/api/probe/probe.sql
                    params:
                      name: sato
                    expect:
                      rowCount: 1
                      rows:
                        - name: sato
                          status: PROBED
                """);
        // A contract test case targeting an identity contract the admin route binds, to exercise
        // contract cases through the sandboxed identity service (no expect: it passes if it runs).
        Files.writeString(target.resolve("tests/studio-contract-test.yml"), """
                tests:
                  - name: the list-users contract runs under the sandbox
                    contract: identity.list-users
                """);
        // A route whose SQL reads `customers` — a table the schema.json overlay above introspects —
        // so the docs route page's inferred data dependency cross-links to that table page (the
        // SQL->table dependency graph). It is never executed (only its spec text is read).
        Files.createDirectories(target.resolve("web/api/deps"));
        Files.writeString(target.resolve("web/api/deps/get.yml"), """
                version: tesseraql/v1
                id: deps.customers
                kind: route
                recipe: query-json

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: deps.sql
                  mode: query

                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """);
        Files.writeString(target.resolve("web/api/deps/deps.sql"),
                "select c.id, c.email from customers c order by c.id\n");
        // A multi-binding query-json route: a main `sql` plus a named `query`, both referenced by the
        // JSON body, so the live render preview must run BOTH against the sandbox (backlog category 3).
        Files.createDirectories(target.resolve("web/api/multi"));
        Files.writeString(target.resolve("web/api/multi/get.yml"), """
                version: tesseraql/v1
                id: multi.report
                kind: route
                recipe: query-json

                security:
                  auth: bearer
                  policy: users.read

                sql:
                  file: main.sql

                queries:
                  active:
                    file: active.sql

                response:
                  json:
                    status: 200
                    body:
                      main: sql.rows
                      active: active.rows
                """);
        Files.writeString(target.resolve("web/api/multi/main.sql"), "select 'main-live' as tag\n");
        Files.writeString(target.resolve("web/api/multi/active.sql"),
                "select 'query-live' as tag\n");
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
