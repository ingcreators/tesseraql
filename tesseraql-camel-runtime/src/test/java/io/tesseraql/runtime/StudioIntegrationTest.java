package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
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
        assertThat(response.body()).contains("web/api/users/search.sql").contains("select");
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
                // The live-search input wires htmx to the search fragment.
                .contains("hx-get=\"/_tesseraql/studio/ui/docs/search\"");
    }

    @Test
    void uiDocsSearchReturnsRankedResultFragment() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/search?q=" + enc("users provision"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // Only the provisioning route matches both terms; the fragment links to its detail page.
        assertThat(response.body()).contains("users.apiProvision")
                .contains("/_tesseraql/studio/ui/docs/route?id=users.apiProvision");
    }

    @Test
    void uiDocsRouteRendersTheRouteReference() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The live-fallback model renders the request surface, security, and bound SQL.
        assertThat(response.body()).contains("users.search").contains("/api/users")
                .contains("query-json").contains("Inputs").contains("search.sql");
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
    void uiDocsRouteRendersTheRunOverlay() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/route?id=" + enc("users.search"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The route page shows the run status card and the bound SQL's line/branch coverage.
        assertThat(response.body()).contains("Last test run").contains("covered")
                .contains("lines").contains("branches");
        // Slice 4: the bound SQL renders line-by-line with per-line coverage classes.
        assertThat(response.body()).contains("hc-code")
                .contains("data-gutter=\"line-numbers\"").contains("data-state=\"covered\"");
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
    void uiDocsSchemaTableRendersColumnsKeysAndForeignKeys() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=" + enc("orders"), true);

        assertThat(response.statusCode()).isEqualTo(200);
        // The table page lists columns, the primary key, and the foreign key linked to its target.
        assertThat(response.body()).contains("Columns").contains("customer_id")
                .contains("Foreign keys").contains("schema/table?ds=main&amp;name=customers");
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

        HttpResponse<String> apply = postForm("/_tesseraql/studio/ui/apply", "path=" + enc(path));
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
        assertThat(index.body()).contains("Setup wizards").contains("SAML SP");

        HttpResponse<String> form = get("/_tesseraql/studio/ui/wizard/saml", true);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("SAML SP wizard").contains("name=\"acsUrl\"");
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

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (auth) {
            request.header("Authorization", "Bearer " + token());
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
            request.header("Authorization", "Bearer " + token());
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "studio-user", "roles", List.of("ADMIN"))));
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
