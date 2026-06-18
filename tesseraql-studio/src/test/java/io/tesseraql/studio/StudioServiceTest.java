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
    void rendersHtmlRouteWithLiveRowSource() {
        StudioService studio = new StudioService(exampleManifest(), true);
        // A stub row source stands in for the runtime's sandboxed query: the sample carries no sql.
        StudioService.RowSource live = (route, dir, context) -> Map.of(
                "rows", List.of(Map.of("id", 9, "name", "Live row", "status", "ACTIVE")),
                "rowCount", 1);

        StudioService.RenderResult result = studio.render(
                "web/users/fragments/table/get.yml", null, "params: {}", live);

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("Live row").contains("ACTIVE");
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
        assertThat(result.error()).contains("query-html/page and query-json");
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
