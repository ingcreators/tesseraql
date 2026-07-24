package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.PreviewResult;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.ScaffoldChecksum;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudioServiceTest {

    private static AppManifest exampleManifest() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void explorerListsRoutesAndJobs() {
        Explorer explorer = new StudioService(exampleManifest(), true).explorer();

        assertThat(explorer.readOnly()).isTrue();
        assertThat(explorer.routes()).anySatisfy(route -> {
            assertThat(route.id()).isEqualTo("users.search");
            assertThat(route.method()).isEqualTo("GET");
            assertThat(route.path()).isEqualTo("/api/users");
            assertThat(route.source()).isEqualTo("web/api/users/get.yml");
        });
        assertThat(explorer.jobs()).anyMatch(job -> job.id().equals("user.dailyMaintenance"));
    }

    @Test
    void editorHrefLinksTheAbsoluteSourcePath() {
        StudioService studio = new StudioService(exampleManifest(), true);
        String href = studio.editorHref("web/api/users/get.yml");
        assertThat(href).startsWith("vscode://file/");
        assertThat(href).endsWith("/examples/user-admin-app/web/api/users/get.yml");
        // Characters outside the unreserved set percent-encode; separators survive.
        assertThat(studio.editorHref("web/items/{id}/get.yml"))
                .endsWith("/web/items/%7Bid%7D/get.yml");
        // The traversal guard holds for the deep link too.
        assertThatThrownBy(() -> studio.editorHref("../secrets.yml"))
                .hasMessageContaining("escapes app home");
    }

    @Test
    void explorerFiltersRoutesAndJobsByQuery() {
        StudioService studio = new StudioService(exampleManifest(), true);

        Explorer all = studio.explorer();
        Explorer filtered = studio.explorer("search");
        // A query narrows the listing to entries whose id/path/recipe/source contains it.
        assertThat(filtered.routes().size()).isLessThan(all.routes().size()).isPositive();
        assertThat(filtered.routes()).anySatisfy(
                route -> assertThat(route.id()).isEqualTo("users.search"));
        assertThat(filtered.routes()).allSatisfy(route -> assertThat(
                (route.id() + route.path() + route.recipe() + route.source() + route.method())
                        .toLowerCase())
                .contains("search"));
        // A blank query is the full listing; a non-matching query is empty.
        assertThat(studio.explorer("   ").routes()).hasSameSizeAs(all.routes());
        assertThat(studio.explorer("no-such-route-xyz").routes()).isEmpty();
    }

    @Test
    void draftsListsPendingDraftsWithConflictAndNewFlags(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/a"));
        Files.writeString(dir.resolve("web/api/a/q.sql"), "select 1\n");
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        assertThat(studio.drafts()).isEmpty();

        // One draft edits an existing source, one is for a not-yet-created file.
        studio.saveDraft("web/api/a/q.sql", "select 2\n");
        studio.saveDraft("web/api/b/q.sql", "select 9\n");

        assertThat(studio.drafts()).hasSize(2);
        assertThat(studio.drafts()).anySatisfy(draft -> {
            assertThat(draft.path()).isEqualTo("web/api/a/q.sql");
            assertThat(draft.isNew()).isFalse();
            assertThat(draft.conflict()).isFalse();
        });
        assertThat(studio.drafts()).anySatisfy(draft -> {
            assertThat(draft.path()).isEqualTo("web/api/b/q.sql");
            assertThat(draft.isNew()).isTrue();
        });

        // A concurrent change to the edited source flags that draft as conflicting.
        Files.writeString(dir.resolve("web/api/a/q.sql"), "select 3\n");
        assertThat(studio.drafts()).filteredOn(draft -> draft.path().equals("web/api/a/q.sql"))
                .singleElement().satisfies(draft -> assertThat(draft.conflict()).isTrue());

        // Discarding removes it from the overview.
        studio.deleteDraft("web/api/a/q.sql");
        assertThat(studio.drafts()).extracting(StudioService.DraftSummary::path)
                .containsExactly("web/api/b/q.sql");
    }

    @Test
    void readsSourceFileAndRejectsTraversal() {
        StudioService studio = new StudioService(exampleManifest(), true);

        assertThat(studio.source("web/api/users/search.sql")).contains("select");

        assertThatThrownBy(() -> studio.source("../../etc/passwd"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes app home");
    }

    @Test
    void readOnlyModeRejectsDraftSaves() {
        StudioService studio = new StudioService(exampleManifest(), true);

        assertThatThrownBy(() -> studio.saveDraft("web/api/users/get.yml", "edited"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void writableModeSavesAndReadsDrafts(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        studio.saveDraft("web/api/x/get.yml", "version: tesseraql/v1\nid: x-edited\n");

        assertThat(studio.readDraft("web/api/x/get.yml")).contains("x-edited");
        // The draft does not touch the source of truth.
        assertThat(studio.source("web/api/x/get.yml")).contains("id: x\n");
    }

    @Test
    void routeFormParsesAndSavesGovernedFieldsPreservingUnmanagedKeys(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                input:
                  q:
                    type: string
                    writable: false
                    mask: last4
                security:
                  auth: bearer
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        StudioService.RouteForm form = studio.routeForm("web/api/x/get.yml");
        assertThat(form.recipe()).isEqualTo("query-json");
        assertThat(form.auth()).isEqualTo("bearer");
        assertThat(form.inputs()).hasSize(1);
        assertThat(form.inputs().get(0).name()).isEqualTo("q");

        // Edit q (managed attrs change, unmanaged writable/mask survive), add a second field.
        studio.routeFormSave("web/api/x/get.yml", "query-json", "browser", "app.read", true,
                java.util.List.of(
                        new StudioService.FormInput("q", "string", true, null, null, "40", null,
                                null, null, null),
                        new StudioService.FormInput("age", "integer", false, "18", "130", null,
                                null, null, null, null)));

        String draft = studio.readDraft("web/api/x/get.yml");
        assertThat(draft).contains("auth: \"browser\"").doesNotContain("bearer");
        assertThat(draft).contains("policy: \"app.read\"").contains("csrf: true");
        assertThat(draft).contains("maxLength: 40").contains("writable: false")
                .contains("mask: \"last4\"");
        assertThat(draft).contains("age").contains("min: 18").contains("max: 130");
        // The mutated document still parses as a route (the save re-validates it).
        assertThat(studio.preview("web/api/x/get.yml", draft).valid()).isTrue();
    }

    @Test
    void routeFormSaveDropsClearedFieldsAndRejectsBadNumbers(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                input:
                  gone:
                    type: string
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        // A row whose name is cleared simply does not survive the rebuild.
        studio.routeFormSave("web/api/x/get.yml", null, null, null, false, java.util.List.of(
                new StudioService.FormInput("", "string", false, null, null, null, null, null,
                        null, null)));
        assertThat(studio.readDraft("web/api/x/get.yml")).doesNotContain("gone")
                .doesNotContain("input:");

        assertThatThrownBy(() -> studio.routeFormSave("web/api/x/get.yml", null, null, null,
                false, java.util.List.of(new StudioService.FormInput("n", "integer", false,
                        "not-a-number", null, null, null, null, null, null))))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("must be a number");
        assertThatThrownBy(() -> studio.routeForm("config/tesseraql.yml"))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("route documents only");
    }

    @Test
    void secretReferencesAreValidatedAndRedactedForDisplay() {
        assertThat(StudioService.isSecretReference("${secret.env.API_KEY}")).isTrue();
        assertThat(StudioService.isSecretReference("${secret.vault.db-pass}")).isTrue();
        assertThat(StudioService.isSecretReference("raw-secret-value")).isFalse();
        assertThat(StudioService.isSecretReference("${secret.env.KEY:fallback}")).isFalse();
        assertThat(StudioService.isSecretReference("${OTHER_VAR}")).isFalse();

        assertThat(StudioService.redactedReference("${secret.env.KEY}"))
                .isEqualTo("${secret.env.KEY}");
        assertThat(StudioService.redactedReference("${secret.env.KEY:dev-fallback}"))
                .isEqualTo("${secret.env.KEY:\u2026}");
        assertThat(StudioService.redactedReference("literal-secret"))
                .isEqualTo("\u2022\u2022\u2022");
    }

    @Test
    void connectorAuthoringWritesOverlayWithReferencesOnly(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  http:
                    outbound:
                      allowedHosts:
                        - api.base.example
                """);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        // Egress add carries the FULL effective list into the overlay (lists replace on merge).
        studio.updateEgressHosts("outbound", "api.new.example", false, "it");
        assertThat(studio.effectiveStringList("tesseraql.http.outbound.allowedHosts"))
                .containsExactly("api.base.example", "api.new.example");
        String overlay = Files.readString(dir.resolve("config/overlay.yml"));
        assertThat(overlay).contains("api.base.example").contains("api.new.example");

        // Removing a base-config host also works through the full-list overlay.
        studio.updateEgressHosts("outbound", "api.base.example", true, "it");
        assertThat(studio.effectiveStringList("tesseraql.http.outbound.allowedHosts"))
                .containsExactly("api.new.example");

        // A webhook verifier only ever stores a secret REFERENCE.
        assertThatThrownBy(() -> studio.writeWebhookVerifier("partner", "raw-secret", null,
                null, null, null, "it"))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("secret reference");
        studio.writeWebhookVerifier("partner", "${secret.env.PARTNER_SECRET}", "X-Sig", null,
                null, "PT5M", "it");
        assertThat(Files.readString(dir.resolve("config/overlay.yml")))
                .contains("${secret.env.PARTNER_SECRET}").contains("X-Sig")
                .contains("PT5M");
        assertThatThrownBy(() -> studio.writeWebhookVerifier("partner",
                "${secret.env.X}", null, null, null, "5 minutes", "it"))
                .hasMessageContaining("ISO-8601");

        // Credentials: the secret-carrying field per type must be a reference.
        assertThatThrownBy(() -> studio.writeConnectorCredential("outbound", "svc", "bearer",
                "plain-token", null, null, null, null, "it"))
                .hasMessageContaining("secret reference");
        studio.writeConnectorCredential("poll", "sftp-drop", "basic", null, "exchange",
                "${secret.env.SFTP_PASS}", null, null, "it");
        assertThat(Files.readString(dir.resolve("config/overlay.yml")))
                .contains("sftp-drop").contains("exchange")
                .contains("${secret.env.SFTP_PASS}");

        // The read model redacts and never echoes a literal.
        Map<String, Object> view = studio.connectorsView();
        assertThat(view.get("outboundHosts")).isEqualTo(java.util.List.of("api.new.example"));
        assertThat(String.valueOf(view.get("webhooks"))).contains("${secret.env.PARTNER_SECRET}");
    }

    @Test
    void recorderMapsInvocationsOntoSqlParamsAndAppendsSuffixedCases(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/things"));
        Files.writeString(dir.resolve("web/api/things/search.sql"), "select 1 as x\n");
        Files.writeString(dir.resolve("web/api/things/get.yml"), """
                version: tesseraql/v1
                id: things.search
                kind: route
                recipe: query-json
                sql:
                  file: search.sql
                  mode: query
                  params:
                    q: query.q
                    owner: params.owner
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/api/things/post.yml"), """
                version: tesseraql/v1
                id: things.create
                kind: route
                recipe: command-json
                sql:
                  file: create.sql
                  mode: update
                response:
                  json:
                    body:
                      affected: sql.affectedRows
                """);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        assertThat(studio.recordability("GET", "/api/things"))
                .containsEntry("recordable", true);
        assertThat(studio.recordability("POST", "/api/things"))
                .containsEntry("recordable", false);
        assertThat(String.valueOf(studio.recordability("GET", "/nope").get("reason")))
                .contains("No served route");
        assertThat(studio.recordedSqlFile("GET", "/api/things"))
                .isEqualTo("web/api/things/search.sql");

        Map<String, Object> params = studio.recordedCaseParams("GET", "/api/things",
                Map.of("q", "sato", "unrelated", "x"), Map.of("owner", "ops"));
        assertThat(params).containsEntry("q", "sato").containsEntry("owner", "ops")
                .doesNotContainKey("unrelated");

        String first = studio.appendRecordedTest("finds sato", "web/api/things/search.sql",
                params, 2, "it");
        String second = studio.appendRecordedTest("finds sato", "web/api/things/search.sql",
                params, 2, "it");
        assertThat(first).isEqualTo("finds sato");
        assertThat(second).isEqualTo("finds sato (2)");
        String suite = Files.readString(dir.resolve("tests/studio-recorded-test.yml"));
        assertThat(suite).contains("finds sato").contains("finds sato (2)")
                .contains("rowCount: 2").contains("web/api/things/search.sql");
    }

    @Test
    void deleteDraftRemovesDraftAndIsIdempotent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        studio.saveDraft("web/api/x/get.yml", "version: tesseraql/v1\nid: x-edited\n");
        assertThat(studio.readDraft("web/api/x/get.yml")).isNotNull();

        assertThat(studio.deleteDraft("web/api/x/get.yml")).isTrue();
        assertThat(studio.readDraft("web/api/x/get.yml")).isNull();
        // Discarding when no draft exists is a no-op success; the source is untouched throughout.
        assertThat(studio.deleteDraft("web/api/x/get.yml")).isFalse();
        assertThat(studio.source("web/api/x/get.yml")).contains("id: x\n");
    }

    @Test
    void readOnlyModeRejectsDiscard() {
        StudioService studio = new StudioService(exampleManifest(), true);

        assertThatThrownBy(() -> studio.deleteDraft("web/api/users/get.yml"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void deleteDraftRejectsTraversal() {
        StudioService studio = new StudioService(exampleManifest(), false);

        assertThatThrownBy(() -> studio.deleteDraft("../../etc/passwd"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes app home");
    }

    @Test
    void previewValidatesRoutesAndSql() {
        StudioService studio = new StudioService(exampleManifest(), true);

        assertThat(studio.preview("web/api/users/get.yml", null).valid()).isTrue();
        assertThat(studio.preview("web/api/users/search.sql", null))
                .satisfies(p -> assertThat(p.valid()).isTrue())
                .satisfies(p -> assertThat(p.result()).doesNotContain("/*"));

        PreviewResult broken = studio.preview("web/api/users/get.yml",
                "version: tesseraql/v1\nid: y\n");
        assertThat(broken.valid()).isFalse();
        assertThat(broken.error()).isNotBlank();
    }

    @Test
    void previewValidatesTemplates() {
        StudioService studio = new StudioService(exampleManifest(), true);

        // A well-formed fragment template parses and renders against an empty model.
        PreviewResult ok = studio.preview("web/users/fragments/table/table.html",
                "<div th:each=\"u : ${users}\" xmlns:th=\"http://www.thymeleaf.org\">"
                        + "<span th:text=\"${u.name}\"></span></div>");
        assertThat(ok.valid()).isTrue();
        assertThat(ok.kind()).isEqualTo("template");

        // Malformed markup (an unclosed attribute quote) is rejected with the parser message.
        PreviewResult malformed = studio.preview("web/users/index.html",
                "<div th:text=\"${x}>oops</div>");
        assertThat(malformed.valid()).isFalse();
        assertThat(malformed.error()).isNotBlank();

        // An unparseable expression is a static authoring error, not a data-dependent one.
        PreviewResult badExpr = studio.preview("web/users/index.html",
                "<p th:text=\"${\" xmlns:th=\"http://www.thymeleaf.org\"></p>");
        assertThat(badExpr.valid()).isFalse();

        // A reference to a template that does not exist is a hard error too.
        PreviewResult badRef = studio.preview("web/users/index.html",
                "<div th:replace=\"~{templates/missing.html :: nav}\"></div>");
        assertThat(badRef.valid()).isFalse();

        // Expression failures that need real route data still count as parsed.
        PreviewResult needsData = studio.preview("web/users/index.html",
                "<p th:text=\"${user.profile.name}\"></p>");
        assertThat(needsData.valid()).isTrue();
        assertThat(needsData.result()).contains("parses");

        // TEXT-mode file templates validate as well.
        PreviewResult text = studio.preview("web/x/conf.yml.tpl", "name: \"[(${appName})]\"\n");
        assertThat(text.valid()).isTrue();
    }

    @Test
    void rendersTemplateAgainstSampleModel() {
        StudioService studio = new StudioService(exampleManifest(), true);

        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/table.html", null,
                "users:\n  - id: 1\n    name: Alice\n    status: active\n");

        assertThat(result.ok()).isTrue();
        assertThat(result.kind()).isEqualTo("html");
        assertThat(result.output()).contains("Alice").contains("active")
                .doesNotContain("No matching users");
    }

    @Test
    void rendersTemplateWithEmptyModelShowsEmptyState() {
        StudioService studio = new StudioService(exampleManifest(), true);

        // Blank sample and no colocated fixture: an empty model renders the empty branch, where
        // preview() could only report the template "parses".
        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/table.html", null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("No matching users");
    }

    @Test
    void renderReportsMalformedSampleData() {
        StudioService studio = new StudioService(exampleManifest(), true);

        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/table.html", null, "users: [unclosed");

        assertThat(result.ok()).isFalse();
        assertThat(result.kind()).isEqualTo("sample");
        assertThat(result.error()).contains("Sample data");
    }

    @Test
    void renderRejectsNonTemplateFiles() {
        StudioService studio = new StudioService(exampleManifest(), true);

        StudioService.RenderResult result = studio.render("web/api/users/search.sql", null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("only available for");
    }

    @Test
    void rendersHtmlRouteAgainstExecutionContext() {
        StudioService studio = new StudioService(exampleManifest(), true);

        // The route maps response.html.model {users: sql.rows, count: sql.rowCount} → table.html.
        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/get.yml", null,
                "sql:\n  rows:\n    - id: 1\n      name: Alice\n      status: active\n  rowCount: 1\n");

        assertThat(result.ok()).isTrue();
        assertThat(result.kind()).isEqualTo("html");
        assertThat(result.output()).contains("Alice").contains("active")
                .doesNotContain("No matching users");
    }

    @Test
    void rendersJsonRouteBodyAgainstExecutionContext() {
        StudioService studio = new StudioService(exampleManifest(), true);

        StudioService.RenderResult result = studio.render("web/api/users/get.yml", null,
                "sql:\n  rows:\n    - id: 7\n      name: Sato\n  rowCount: 1\n"
                        + "params:\n  limit: 50\n  offset: 0\n");

        assertThat(result.ok()).isTrue();
        assertThat(result.kind()).isEqualTo("json");
        // The body template {data: sql.rows, meta: {count: sql.rowCount, limit: params.limit, …}}
        // resolves against the fixture context and pretty-prints.
        assertThat(result.output()).contains("\"data\"").contains("Sato")
                .contains("\"count\" : 1").contains("\"limit\" : 50");
    }

    @Test
    void rendersJsonRouteAppliesTheFieldMaskWhenFieldsArePresent(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Path route = dir.resolve("web/api/x/get.yml");
        Files.writeString(route, """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                response:
                  json:
                    body:
                      email: params.email
                    fields:
                      email:
                        mask: email
                """);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), true);

        // The mask receives the route's fields and the resolved body; its output is what renders.
        StudioService.FieldMask mask = (fields, body, context) -> {
            assertThat(fields).containsKey("email");
            return Map.of("masked", true);
        };
        StudioService.RenderResult masked = studio.render("web/api/x/get.yml", null,
                "params:\n  email: sato@example.com\n", null, mask);
        assertThat(masked.ok()).isTrue();
        assertThat(masked.output()).contains("\"masked\" : true")
                .doesNotContain("sato@example.com");

        // A route with no fields never invokes the mask (a throwing mask proves it is not called).
        Files.writeString(route, """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                response:
                  json:
                    body:
                      email: params.email
                """);
        StudioService.FieldMask boom = (fields, body, context) -> {
            throw new IllegalStateException("must not run when there are no fields");
        };
        StudioService.RenderResult plain = studio.render("web/api/x/get.yml", null,
                "params:\n  email: sato@example.com\n", null, boom);
        assertThat(plain.ok()).isTrue();
        assertThat(plain.output()).contains("sato@example.com");
    }

    @Test
    void rendersExportPdfRouteThroughThePdfRenderCallback(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x.print
                kind: route
                recipe: query-export
                sql:
                  file: print.sql
                export:
                  format: pdf
                  filename: x.pdf
                  template: print.html
                  columns:
                    - { name: name, header: Name }
                    - { name: status, header: Status }
                """);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), true);

        byte[] fakePdf = {'%', 'P', 'D', 'F', '-', '1', '.', '4'};
        StudioService.PdfRender pdf = (export, routeDir, rows) -> {
            assertThat(export.format()).isEqualTo("pdf");
            assertThat(rows).hasSize(1); // from the sample's sql.rows
            return fakePdf;
        };
        StudioService.RenderResult result = studio.render("web/api/x/get.yml", null,
                "sql:\n  rows:\n    - name: Sato\n      status: ACTIVE\n", null, null, pdf);
        assertThat(result.ok()).isTrue();
        assertThat(result.kind()).isEqualTo("pdf");
        assertThat(result.output()).isEqualTo("data:application/pdf;base64,"
                + java.util.Base64.getEncoder().encodeToString(fakePdf));

        // With no renderer (the optional module is absent) the preview reports it unavailable.
        StudioService.RenderResult unavailable = studio.render("web/api/x/get.yml", null,
                "sql:\n  rows: []\n", null, null, null);
        assertThat(unavailable.ok()).isFalse();
        assertThat(unavailable.error()).contains("tesseraql-pdf module");
    }

    @Test
    void rendersHtmlRouteWithLiveRowSource() {
        StudioService studio = new StudioService(exampleManifest(), true);
        // A stub row source stands in for the runtime's sandboxed query: the sample carries no sql.
        // The source keys each result by its model name — the main query under `sql`.
        StudioService.RowSource live = (route, dir, context) -> Map.of("sql", Map.of(
                "rows", List.of(Map.of("id", 9, "name", "Live row", "status", "ACTIVE")),
                "rowCount", 1));

        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/get.yml", null, "params: {}", live);

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("Live row").contains("ACTIVE");
    }

    @Test
    void liveRowSourceInjectsEveryNamedQueryResult(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/report"));
        // A multi-binding query-json route: the body references the main `sql` and a named query.
        Files.writeString(dir.resolve("web/report/get.yml"), """
                version: tesseraql/v1
                id: report.get
                kind: route
                recipe: query-json
                sql:
                  file: main.sql
                queries:
                  totals:
                    file: totals.sql
                response:
                  json:
                    status: 200
                    body:
                      items: sql.rows
                      total: totals.rows
                """);
        Files.writeString(dir.resolve("web/report/main.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/report/totals.sql"), "select 2\n");

        StudioService studio = new StudioService(new ManifestLoader().load(dir), true);
        // The runtime's RowSource keys the main query under `sql` and the named query under its name.
        StudioService.RowSource live = (route, routeDir, context) -> Map.of(
                "sql", Map.of("rows", List.of(Map.of("id", 1)), "rowCount", 1),
                "totals", Map.of("rows", List.of(Map.of("n", 42)), "rowCount", 1));

        StudioService.RenderResult result = studio.render("web/report/get.yml", null, "{}", live);

        assertThat(result.ok()).isTrue();
        // Both the main `sql.rows` and the named query `totals.rows` are injected into the JSON
        // (the value 42 only appears if the named query result reached the body).
        assertThat(result.output()).contains("items").contains("total").contains("42");
    }

    @Test
    void renderSurfacesLiveDataFailure() {
        StudioService studio = new StudioService(exampleManifest(), true);
        StudioService.RowSource boom = (route, dir, context) -> {
            throw new IllegalStateException("connection refused");
        };

        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/get.yml", null, "params: {}", boom);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Live data").contains("connection refused");
    }

    private static StudioService migrationStudio(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        return new StudioService(new ManifestLoader().load(dir), false); // writable
    }

    @Test
    void nextMigrationVersionIsNumericNotLexicographic(@TempDir Path dir) throws Exception {
        StudioService studio = migrationStudio(dir);
        assertThat(studio.nextMigrationVersion("main", null)).isEqualTo("1");

        Files.createDirectories(dir.resolve("db/migration"));
        Files.writeString(dir.resolve("db/migration/V1__a.sql"), "select 1\n");
        Files.writeString(dir.resolve("db/migration/V2__b.sql"), "select 1\n");
        Files.writeString(dir.resolve("db/migration/V10__c.sql"), "select 1\n");
        Files.writeString(dir.resolve("db/migration/R__view.sql"), "select 1\n"); // ignored
        // 11, not 3 (lexicographic V10 < V2 would have given 3) — the framework orders numerically.
        assertThat(new StudioService(new ManifestLoader().load(dir), false)
                .nextMigrationVersion("main", null)).isEqualTo("11");
    }

    @Test
    void createMigrationWritesAVersionedFileWithTheDdlAndAudits(@TempDir Path dir)
            throws Exception {
        StudioService studio = migrationStudio(dir);

        StudioService.MigrationResult result = studio.createMigration("main", null, false,
                "Add user index", "create index ix_user on users(name);", false, "alice");

        assertThat(result.path()).isEqualTo("db/migration/V1__Add_user_index.sql");
        assertThat(result.version()).isEqualTo("1");
        assertThat(result.repeatable()).isFalse();
        assertThat(Files.readString(dir.resolve(result.path())))
                .contains("create index ix_user on users(name);");
        // The write is recorded to the audit trail (like apply/scaffold).
        assertThat(studio.auditEntries(10)).anySatisfy(entry -> assertThat(entry.action())
                .isEqualTo("migration"));
    }

    @Test
    void auditPageSortsTheFilteredLogByColumn(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        Path log = dir.resolve("work/studio/audit/audit.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, """
                {"at":"2026-06-18T10:00:00Z","actor":"carol","action":"apply","target":"web/c.yml"}
                {"at":"2026-06-18T11:00:00Z","actor":"alice","action":"scaffold","target":"widgets"}
                {"at":"2026-06-18T12:00:00Z","actor":"bob","action":"apply","target":"web/b.yml"}
                """);

        // Platform-UX I2: the whole filtered log is sorted before paging.
        assertThat(studio.auditPage(null, "actor", "asc", 1, 50).entries())
                .extracting(StudioService.AuditEntry::actor)
                .containsExactly("alice", "bob", "carol");
        assertThat(studio.auditPage(null, "actor", "desc", 1, 50).entries())
                .extracting(StudioService.AuditEntry::actor)
                .containsExactly("carol", "bob", "alice");
        // No explicit sort = newest first (at desc): bob 12:00, alice 11:00, carol 10:00.
        assertThat(studio.auditPage(null, null, null, 1, 50).entries())
                .extracting(StudioService.AuditEntry::actor)
                .containsExactly("bob", "alice", "carol");
        // Sort composes with the filter: only bob's apply matches, still sorted.
        assertThat(studio.auditPage("bob", "actor", "asc", 1, 50).entries())
                .extracting(StudioService.AuditEntry::actor).containsExactly("bob");
    }

    @Test
    void createMigrationSupportsRepeatableVendorAndOtherDatasource(@TempDir Path dir)
            throws Exception {
        StudioService studio = migrationStudio(dir);

        assertThat(studio.createMigration("main", null, true, "user summary view", "", false, null)
                .path()).isEqualTo("db/migration/R__user_summary_view.sql");
        assertThat(studio.createMigration("main", "postgres", false, "x", "", false, null).path())
                .isEqualTo("db/migration-postgres/V1__x.sql");
        assertThat(studio.createMigration("reporting", null, false, "y", "", false, null).path())
                .isEqualTo("db/reporting/migration/V1__y.sql");
    }

    @Test
    void createMigrationRefusesToOverwriteUnlessForcedAndNeedsADescription(@TempDir Path dir)
            throws Exception {
        StudioService studio = migrationStudio(dir);
        studio.createMigration("main", null, true, "v", "first\n", false, null);

        // The same repeatable name already exists.
        assertThatThrownBy(() -> studio.createMigration("main", null, true, "v", "second\n", false,
                null)).isInstanceOf(TqlException.class).hasMessageContaining("already exists");
        // force overwrites.
        studio.createMigration("main", null, true, "v", "second\n", true, null);
        assertThat(Files.readString(dir.resolve("db/migration/R__v.sql"))).contains("second");
        // An empty description is rejected.
        assertThatThrownBy(() -> studio.createMigration("main", null, false, "  ", "", false, null))
                .isInstanceOf(TqlException.class).hasMessageContaining("description");
    }

    @Test
    void isMigrationPathMatchesFlywayLocationsOnly() {
        assertThat(StudioService.isMigrationPath("db/migration/V1__a.sql")).isTrue();
        assertThat(StudioService.isMigrationPath("db/migration-postgres/V1__a.sql")).isTrue();
        assertThat(StudioService.isMigrationPath("db/reporting/migration/R__v.sql")).isTrue();
        assertThat(StudioService.isMigrationPath("db/migration/notes.txt")).isFalse();
        assertThat(StudioService.isMigrationPath("web/api/users/search.sql")).isFalse();
        assertThat(StudioService.isMigrationPath(null)).isFalse();
    }

    @Test
    void dryRunMigrationForwardsTheContentForAMigrationPathAndDeclinesOthers(@TempDir Path dir)
            throws Exception {
        StudioService studio = migrationStudio(dir);
        // The stub callback echoes the DDL it was handed, so we can see which content was used.
        StudioService.DdlDryRun echo = ddl -> StudioService.DryRunResult.failed("got: " + ddl);

        // A non-migration path is declined without invoking the callback.
        StudioService.DryRunResult declined = studio.dryRunMigration("web/api/x/get.yml", "x",
                echo);
        assertThat(declined.ran()).isFalse();
        assertThat(declined.message()).contains("migration files");

        // For a migration path the supplied (live editor) content is forwarded to the runner.
        StudioService.DryRunResult ran = studio.dryRunMigration("db/migration/V1__a.sql",
                "create table t (id int);", echo);
        assertThat(ran.ran()).isTrue();
        assertThat(ran.message()).contains("create table t (id int);");
    }

    @Test
    void createMigrationIsRejectedInReadOnlyMode(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService readOnly = new StudioService(new ManifestLoader().load(dir), true); // read-only

        assertThatThrownBy(
                () -> readOnly.createMigration("main", null, false, "x", "", false, null))
                .isInstanceOf(TqlException.class).hasMessageContaining("read-only");
    }

    @Test
    void renderRejectsRouteWithoutHtmlOrJsonResponse(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/go"));
        Files.writeString(dir.resolve("web/go/post.yml"), """
                version: tesseraql/v1
                id: go.redirect
                kind: route
                recipe: command
                sql:
                  file: go.sql
                response:
                  redirect:
                    location: /done
                """);

        StudioService studio = new StudioService(new ManifestLoader().load(dir), true);
        StudioService.RenderResult result = studio.render("web/go/post.yml", null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("query-html/page, query-json, and query-export");
    }

    @Test
    void sampleModelDiscoversColocatedFixtureAndRenderFallsBackToIt(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/page"));
        Files.writeString(dir.resolve("web/page/card.html"),
                "<p xmlns:th=\"http://www.thymeleaf.org\" th:text=\"${title}\">x</p>");
        Files.writeString(dir.resolve("web/page/card.sample.yml"), "title: Hello fixture\n");

        StudioService studio = new StudioService(new ManifestLoader().load(dir), true);

        // The colocated *.sample.yml is discovered so the editor can prefill it.
        assertThat(studio.sampleModel("web/page/card.html")).contains("Hello fixture");
        // A non-template file has no fixture.
        assertThat(studio.sampleModel("web/page/card.sql")).isNull();

        // A blank sample model falls back to the colocated fixture at render time.
        StudioService.RenderResult result = studio.render("web/page/card.html", null, "  ");
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("Hello fixture");
    }

    @Test
    void applyPromotesDraftToSourceThenReloadReflectsIt(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        studio.saveDraft("web/api/x/get.yml", """
                version: tesseraql/v1
                id: x.renamed
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        studio.applyDraft("web/api/x/get.yml");
        assertThat(studio.source("web/api/x/get.yml")).contains("id: x.renamed");
        assertThat(studio.readDraft("web/api/x/get.yml")).isNull();

        assertThat(studio.reload().routes()).anyMatch(route -> route.id().equals("x.renamed"));
    }

    @Test
    void applyDetectsConcurrentSourceChangeAndForceOverrides(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        String source = """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """;
        Files.writeString(dir.resolve("web/api/x/get.yml"), source);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        studio.saveDraft("web/api/x/get.yml", source.replace("id: x", "id: x-edited"));
        // The draft is based on the current source, so there is no conflict.
        assertThat(studio.draftConflicts("web/api/x/get.yml")).isFalse();

        // A concurrent change to the source under the draft is detected.
        Files.writeString(dir.resolve("web/api/x/get.yml"), source.replace("id: x", "id: x-other"));
        assertThat(studio.draftConflicts("web/api/x/get.yml")).isTrue();

        // Applying without force is rejected so the draft cannot clobber the other change.
        assertThatThrownBy(() -> studio.applyDraft("web/api/x/get.yml"))
                .isInstanceOf(TqlException.class).hasMessageContaining("changed since");
        assertThat(studio.source("web/api/x/get.yml")).contains("id: x-other");

        // force overwrites the changed source with the draft and clears the draft + recorded base.
        studio.applyDraft("web/api/x/get.yml", true);
        assertThat(studio.source("web/api/x/get.yml")).contains("id: x-edited");
        assertThat(studio.readDraft("web/api/x/get.yml")).isNull();
        assertThat(studio.draftConflicts("web/api/x/get.yml")).isFalse();
    }

    @Test
    void applyRejectsInvalidDraftAndReadOnly(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        StudioService writable = new StudioService(new ManifestLoader().load(dir), false);
        writable.saveDraft("web/api/x/get.yml", "version: tesseraql/v1\nid: y\n");
        assertThatThrownBy(() -> writable.applyDraft("web/api/x/get.yml"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("does not compile");

        StudioService readOnly = new StudioService(new ManifestLoader().load(dir), true);
        assertThatThrownBy(() -> readOnly.applyDraft("web/api/x/get.yml"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void scaffoldPreviewGeneratesCrudSliceWithPerFileStatus(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        TableSchema table = widgetsSchema();

        // A fresh app home: every generated file is new and the apply would write them all.
        StudioService.ScaffoldPreview first = studio.scaffoldPreview(table);
        assertThat(first.table()).isEqualTo("widgets");
        assertThat(first.files()).isNotEmpty()
                .allSatisfy(file -> assertThat(file.status()).isEqualTo("new"));
        assertThat(first.writeCount()).isEqualTo(first.total());
        assertThat(first.conflictCount()).isZero();
        assertThat(first.files()).anyMatch(file -> file.path().equals("web/widgets/get.yml"));

        StudioService.ScaffoldFile sample = first.files().get(0);
        Path target = dir.resolve(sample.path());
        Files.createDirectories(target.getParent());

        // A byte-identical (stamped) file on disk reads back as unchanged.
        Files.writeString(target, ScaffoldChecksum.stamp(sample.path(), sample.content()));
        assertThat(statusOf(studio.scaffoldPreview(table), sample.path())).isEqualTo("unchanged");

        // A pristine but differently-generated file (a valid marker over older content) regenerates.
        Files.writeString(target,
                ScaffoldChecksum.stamp(sample.path(), sample.content() + "\n# older\n"));
        assertThat(statusOf(studio.scaffoldPreview(table), sample.path())).isEqualTo("regenerate");

        // A user-edited (marker-less) file is a conflict the apply would skip.
        Files.writeString(target, "hand-written, no scaffold marker\n");
        StudioService.ScaffoldPreview edited = studio.scaffoldPreview(table);
        assertThat(statusOf(edited, sample.path())).isEqualTo("conflict");
        assertThat(edited.conflictCount()).isEqualTo(1);
        assertThat(edited.writeCount()).isEqualTo(edited.total() - 1);
    }

    @Test
    void scaffoldApplyWritesSliceFlagsNewRoutesAndIsIdempotent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        TableSchema table = widgetsSchema();

        // A fresh apply writes the slice to disk; every written route is new (none in the manifest).
        StudioService.ScaffoldResult first = studio.scaffoldApply(table, false);
        assertThat(first.blocked()).isFalse();
        assertThat(first.written()).contains("web/widgets/get.yml");
        assertThat(first.newRoutes()).contains("web/widgets/get.yml");
        assertThat(Files.isRegularFile(dir.resolve("web/widgets/get.yml"))).isTrue();
        // A generated SQL file is written but is not a route needing a restart.
        assertThat(first.newRoutes()).noneMatch(path -> path.endsWith(".sql"));

        // Re-applying is idempotent: nothing written, every file reported unchanged.
        StudioService.ScaffoldResult second = studio.scaffoldApply(table, false);
        assertThat(second.written()).isEmpty();
        assertThat(second.unchanged()).contains("web/widgets/get.yml");
        assertThat(second.newRoutes()).isEmpty();
    }

    @Test
    void scaffoldApplyRejectedInReadOnlyMode(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService readOnly = new StudioService(new ManifestLoader().load(dir), true);

        assertThatThrownBy(() -> readOnly.scaffoldApply(widgetsSchema(), false))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void applyAndScaffoldRecordTheAuditTrail(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        String source = """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """;
        Files.writeString(dir.resolve("web/api/x/get.yml"), source);
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        assertThat(studio.auditEntries(10)).isEmpty();

        // Applying a draft and scaffolding a table each stamp an audit entry with their actor.
        studio.saveDraft("web/api/x/get.yml", source.replace("id: x", "id: x-2"));
        studio.applyDraft("web/api/x/get.yml", false, "alice");
        studio.scaffoldApply(widgetsSchema(), false, "bob");

        List<StudioService.AuditEntry> entries = studio.auditEntries(10);
        assertThat(entries).hasSize(2);
        // Newest first: the scaffold, then the apply.
        assertThat(entries.get(0)).satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("scaffold");
            assertThat(entry.actor()).isEqualTo("bob");
            assertThat(entry.target()).isEqualTo("widgets");
            assertThat(entry.at()).isNotBlank();
        });
        assertThat(entries.get(1)).satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("apply");
            assertThat(entry.actor()).isEqualTo("alice");
            assertThat(entry.target()).isEqualTo("web/api/x/get.yml");
        });

        // A null actor is recorded as "unknown".
        studio.saveDraft("web/api/x/get.yml", source.replace("id: x", "id: x-3"));
        studio.applyDraft("web/api/x/get.yml", false, null);
        assertThat(studio.auditEntries(1).get(0).actor()).isEqualTo("unknown");
    }

    @Test
    void scaffoldPreviewRejectsTableWithoutSingleColumnPrimaryKey() {
        StudioService studio = new StudioService(exampleManifest(), true);
        TableSchema noKey = new TableSchema("t",
                List.of(new TableSchema.Column("a", java.sql.Types.VARCHAR, "varchar", 10, 0,
                        false, false, false)),
                List.of(), Map.of());

        assertThatThrownBy(() -> studio.scaffoldPreview(noKey))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("primary key");
    }

    @Test
    void newRouteDraftSavesAValidStarterAndRejectsBadInput(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);

        studio.newRouteDraft("web/api/widgets/get.yml", "query-json");
        String draft = studio.readDraft("web/api/widgets/get.yml");
        assertThat(draft).contains("id: api.widgets.get").contains("recipe: query-json");
        // The starter parses as a valid route, so the editor's apply flow accepts it.
        assertThat(studio.preview("web/api/widgets/get.yml", draft).valid()).isTrue();

        // query-html carries the response/CSP scaffolding; command-json the write shape.
        studio.newRouteDraft("web/page/get.yml", "query-html");
        assertThat(studio.readDraft("web/page/get.yml")).contains("recipe: query-html")
                .contains("template: page.html").contains("Content-Security-Policy");
        studio.newRouteDraft("web/api/widgets/post.yml", "command-json");
        assertThat(studio.readDraft("web/api/widgets/post.yml")).contains("recipe: command-json")
                .contains("affected: sql.affectedRows");

        // A non-route path and an already-existing file are rejected.
        assertThatThrownBy(() -> studio.newRouteDraft("web/api/widgets/notes.yml", "query-json"))
                .isInstanceOf(TqlException.class).hasMessageContaining("web/**");
        Files.createDirectories(dir.resolve("web/api/exists"));
        Files.writeString(dir.resolve("web/api/exists/get.yml"), "version: tesseraql/v1\nid: e\n");
        assertThatThrownBy(() -> studio.newRouteDraft("web/api/exists/get.yml", "query-json"))
                .isInstanceOf(TqlException.class).hasMessageContaining("already exists");
    }

    @Test
    void newRouteDraftRejectedInReadOnlyMode(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        StudioService readOnly = new StudioService(new ManifestLoader().load(dir), true);

        assertThatThrownBy(() -> readOnly.newRouteDraft("web/api/x/get.yml", "query-json"))
                .isInstanceOf(TqlException.class).hasMessageContaining("read-only");
    }

    private static String statusOf(StudioService.ScaffoldPreview preview, String path) {
        return preview.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst().orElseThrow().status();
    }

    private static TableSchema widgetsSchema() {
        return new TableSchema("widgets",
                List.of(
                        new TableSchema.Column("id", java.sql.Types.BIGINT, "bigint", 19, 0,
                                false, true, false),
                        new TableSchema.Column("name", java.sql.Types.VARCHAR, "varchar", 100, 0,
                                false, false, false)),
                List.of("id"), Map.of());
    }
}
