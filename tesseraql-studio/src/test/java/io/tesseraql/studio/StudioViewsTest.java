package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.JobSummary;
import io.tesseraql.studio.StudioService.PreviewResult;
import io.tesseraql.studio.StudioService.RouteSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StudioViewsTest {

    @Test
    void explorerBuildsTemplateReadyModel() {
        Explorer explorer = new Explorer("user-admin", false,
                List.of(new RouteSummary("users.search", "GET", "/api/users", "query-json",
                        "web/api/users/get.yml")),
                List.of(new JobSummary("nightly", "batch-pipeline", "jobs/nightly.yml")));

        Map<String, Object> model = StudioViews.explorer(explorer);

        assertThat(model.get("appName")).isEqualTo("user-admin");
        assertThat(model.get("editable")).isEqualTo(true);
        assertThat(model.get("readOnly")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) model.get("routes");
        assertThat(routes.get(0)).containsEntry("id", "users.search")
                .containsEntry("sourceUrl",
                        "/_tesseraql/studio/ui/source?path=web%2Fapi%2Fusers%2Fget.yml");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) model.get("jobs");
        assertThat(jobs.get(0)).containsEntry("id", "nightly");
        assertThat(model.get("hasRoutes")).isEqualTo(true);
        assertThat(model.get("hasJobs")).isEqualTo(true);
    }

    @Test
    void explorerHandlesEmptyApp() {
        Map<String, Object> model = StudioViews.explorer(
                new Explorer("empty", true, List.of(), List.of()));

        assertThat(model.get("readOnly")).isEqualTo(true);
        assertThat(model.get("editable")).isEqualTo(false);
        assertThat(model.get("hasRoutes")).isEqualTo(false);
        assertThat(model.get("hasJobs")).isEqualTo(false);
        assertThat(model.get("count")).isEqualTo(0);
        assertThat(tree(model).get("empty")).isEqualTo(true);
    }

    @Test
    void explorerFoldsSourcesIntoADirectoryTree() {
        Explorer explorer = new Explorer("app", false,
                List.of(new RouteSummary("users.search", "GET", "/api/users", "query-json",
                        "web/api/users/get.yml"),
                        new RouteSummary("users.create", "POST", "/api/users", "command-json",
                                "web/api/users/post.yml")),
                List.of(new JobSummary("nightly", "batch-pipeline", "batch/nightly/job.yml")));

        Map<String, Object> tree = tree(StudioViews.explorer(explorer));

        // Top-level folders are batch/ and web/, sorted by name.
        assertThat(subfolders(tree)).extracting(folder -> folder.get("name"))
                .containsExactly("batch", "web");
        // web → api → users holds both route leaves, sorted by file name (get.yml, post.yml).
        Map<String, Object> users = folder(subfolders(folder(subfolders(
                folder(subfolders(tree), "web")), "api")), "users");
        assertThat(leaves(users)).extracting(leaf -> leaf.get("label"))
                .containsExactly("users.search", "users.create");
        assertThat(leaves(users).get(0)).containsEntry("badge", "GET")
                .containsEntry("sourceUrl",
                        "/_tesseraql/studio/ui/source?path=web%2Fapi%2Fusers%2Fget.yml");
        // The job is a leaf carrying the synthetic "job" badge.
        Map<String, Object> nightly = folder(subfolders(folder(subfolders(tree), "batch")),
                "nightly");
        assertThat(leaves(nightly)).singleElement()
                .satisfies(leaf -> assertThat(leaf).containsEntry("label", "nightly")
                        .containsEntry("badge", "job"));
    }

    @Test
    void draftsBuildsOverviewModel() {
        Map<String, Object> model = StudioViews.drafts(List.of(
                new StudioService.DraftSummary("web/api/a/get.yml", false, false),
                new StudioService.DraftSummary("web/api/b/get.yml", true, true)));

        assertThat(model).containsEntry("count", 2).containsEntry("hasDrafts", true)
                .containsEntry("conflictCount", 1).containsEntry("hasConflicts", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("drafts");
        assertThat(rows.get(0)).containsEntry("path", "web/api/a/get.yml")
                .containsEntry("kind", "edit").containsEntry("conflict", false)
                .containsEntry("sourceUrl",
                        "/_tesseraql/studio/ui/source?path=web%2Fapi%2Fa%2Fget.yml");
        assertThat(rows.get(1)).containsEntry("kind", "new").containsEntry("conflict", true);
    }

    @Test
    void draftsFilterKeepsOnlyMatchingPaths() {
        // Platform-UX H5: a query narrows the drafts list (case-insensitive substring over the path).
        Map<String, Object> model = StudioViews.drafts(List.of(
                new StudioService.DraftSummary("web/api/users/get.yml", false, false),
                new StudioService.DraftSummary("web/api/orders/get.yml", false, false)), "ORDERS");

        assertThat(model).containsEntry("count", 1).containsEntry("query", "ORDERS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("drafts");
        assertThat(rows).singleElement().extracting(r -> r.get("path"))
                .isEqualTo("web/api/orders/get.yml");
    }

    @Test
    void sourceModelCarriesTheEditorLanguage() {
        // The data-lang grammar for live-highlighting the editable field, by extension (backlog E).
        assertThat(StudioViews.source("web/api/users/search.sql", "", false, false, "", null))
                .containsEntry("lang", "tql-sql");
        assertThat(StudioViews.source("web/api/users/get.yml", "", false, false, "", null))
                .containsEntry("lang", "yaml");
        assertThat(StudioViews.source("web/users/table.html", "", false, false, "", null))
                .containsEntry("lang", "html");
        assertThat(StudioViews.source("web/x/wizard.yml.tpl", "", false, false, "", null))
                .containsEntry("lang", "yaml");
        // An unrecognised extension leaves the field a plain textarea.
        assertThat(StudioViews.source("docs/notes.md", "", false, false, "", null))
                .containsEntry("lang", "");
    }

    @Test
    void renderModelCarriesPdfDataUrl() {
        Map<String, Object> model = StudioViews.render(
                StudioService.RenderResult.ok("pdf", "data:application/pdf;base64,JVBERi0="));

        assertThat(model).containsEntry("isPdf", true).containsEntry("isHtml", false)
                .containsEntry("pdfUrl", "data:application/pdf;base64,JVBERi0=")
                .doesNotContainKey("outputHtml");
    }

    @Test
    void auditBuildsTrailModel() {
        Map<String, Object> model = StudioViews.audit(List.of(
                new StudioService.AuditEntry("2026-06-18T10:00:00Z", "alice", "apply",
                        "web/api/x/get.yml"),
                new StudioService.AuditEntry("2026-06-18T09:00:00Z", "bob", "scaffold",
                        "widgets")));

        assertThat(model).containsEntry("count", 2).containsEntry("hasEntries", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("entries");
        assertThat(rows.get(0)).containsEntry("actor", "alice").containsEntry("action", "apply")
                .containsEntry("targetUrl",
                        "/_tesseraql/studio/ui/source?path=web%2Fapi%2Fx%2Fget.yml");
        // A scaffold targets a table name, not a linkable source path.
        assertThat(rows.get(1).get("targetUrl")).isNull();
    }

    @Test
    void auditPageBuildsPaginationModel() {
        // Platform-UX I3: the paged overload adds the pagination coordinates the nav renders from.
        StudioService.AuditEntry e = new StudioService.AuditEntry("t", "a", "apply",
                "web/x/get.yml");
        Map<String, Object> mid = StudioViews.audit(
                new StudioService.AuditPage(List.of(e), 2, 50, 120), "q");
        assertThat(mid).containsEntry("total", 120).containsEntry("pageNum", 2)
                .containsEntry("totalPages", 3).containsEntry("paged", true)
                .containsEntry("hasPrev", true).containsEntry("hasNext", true)
                .containsEntry("prevPage", 1).containsEntry("nextPage", 3)
                .containsEntry("query", "q").containsEntry("hasEntries", true);

        // Last page: no next. Single page: not paged.
        assertThat(StudioViews.audit(new StudioService.AuditPage(List.of(e), 3, 50, 120), null))
                .containsEntry("hasNext", false).containsEntry("hasPrev", true);
        assertThat(StudioViews.audit(new StudioService.AuditPage(List.of(e), 1, 50, 10), null))
                .containsEntry("paged", false).containsEntry("totalPages", 1);
    }

    @Test
    void auditSortLinksCarryTheFilterAndPreserveSortOnRefilter() {
        // Platform-UX I2: the sort state, per-column header link / aria-sort, and the hx-vals that
        // keeps the sort when the filter re-requests #audit-table.
        StudioService.AuditEntry e = new StudioService.AuditEntry("t", "a", "apply",
                "web/x/get.yml");
        StudioService.AuditPage page = new StudioService.AuditPage(List.of(e), 1, 50, 1);

        // Default = newest first (at desc); the active 'at' header flips to ascending.
        Map<String, Object> def = StudioViews.audit(page, null, null, null);
        assertThat(def).containsEntry("sortKey", "at").containsEntry("sortDir", "desc");
        @SuppressWarnings("unchecked")
        Map<String, String> aria = (Map<String, String>) def.get("ariaSort");
        assertThat(aria).containsEntry("at", "descending").containsEntry("actor", "none");
        @SuppressWarnings("unchecked")
        Map<String, String> href = (Map<String, String>) def.get("sortHref");
        assertThat(href.get("at")).contains("sort=at&dir=asc");
        assertThat((String) def.get("hxVals")).contains("\"sort\": \"at\"")
                .contains("\"dir\": \"desc\"");

        // Explicit actor-asc with a filter: the sort link carries q, hx-vals carries the sort.
        Map<String, Object> byActor = StudioViews.audit(page, "alice", "actor", "asc");
        assertThat(byActor).containsEntry("sortKey", "actor").containsEntry("sortDir", "asc");
        @SuppressWarnings("unchecked")
        Map<String, String> href2 = (Map<String, String>) byActor.get("sortHref");
        assertThat(href2.get("actor")).contains("sort=actor&dir=desc").contains("q=alice");
        assertThat((String) byActor.get("hxVals")).contains("\"sort\": \"actor\"");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tree(Map<String, Object> model) {
        return (Map<String, Object>) model.get("tree");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subfolders(Map<String, Object> folder) {
        return (List<Map<String, Object>>) folder.get("folders");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> leaves(Map<String, Object> folder) {
        return (List<Map<String, Object>>) folder.get("leaves");
    }

    private static Map<String, Object> folder(List<Map<String, Object>> folders, String name) {
        return folders.stream().filter(folder -> name.equals(folder.get("name")))
                .findFirst().orElseThrow();
    }

    @Test
    void sourceFlagsARouteSqlFileForTheInEditorSqlBuilder() {
        // A route SQL file offers the in-editor SQL builder; a migration SQL or a route yml does not.
        assertThat(StudioViews.source("web/api/users/search.sql", "", false, false, "", null))
                .containsEntry("isRouteSql", true);
        assertThat(StudioViews.source("db/migration/V1__init.sql", "", false, false, "", null))
                .containsEntry("isRouteSql", false);
        assertThat(StudioViews.source("web/api/users/get.yml", "", false, false, "", null))
                .containsEntry("isRouteSql", false);
    }

    @Test
    void sourceBuildsModel() {
        Map<String, Object> model = StudioViews.source("a.sql", "select 1", false, false,
                "select 1", null);

        assertThat(model).containsEntry("path", "a.sql")
                .containsEntry("content", "select 1")
                .containsEntry("editable", true)
                .containsEntry("readOnly", false)
                .containsEntry("hasDraft", false)
                .containsEntry("sourceContent", "select 1")
                // A non-template file offers no rendered-preview panel and no sample fixture.
                .containsEntry("isTemplate", false)
                .containsEntry("isHtmlTemplate", false)
                .containsEntry("sampleModel", "");
    }

    @Test
    void sourceModelCarriesTemplateFlagsAndSampleFixture() {
        Map<String, Object> model = StudioViews.source("web/users/fragments/table/table.html",
                "<div th:text=\"${x}\"></div>", false, false,
                "<div th:text=\"${x}\"></div>", "x: hello\n");

        assertThat(model).containsEntry("isTemplate", true)
                .containsEntry("isHtmlTemplate", true)
                .containsEntry("sampleModel", "x: hello\n");

        // A TEXT-mode .tpl template is a template, but not an HTML one (no visual iframe preview).
        Map<String, Object> tpl = StudioViews.source("web/x/conf.yml.tpl", "[(${n})]", false, false,
                "[(${n})]", null);
        assertThat(tpl).containsEntry("isTemplate", true)
                .containsEntry("isHtmlTemplate", false)
                .containsEntry("sampleModel", "");
    }

    @Test
    void sourceModelMarksWebRoutesRenderable() {
        // A web route document renders against an execution-context fixture, not template vars.
        Map<String, Object> route = StudioViews.source("web/api/users/get.yml", "id: x", false,
                false, "id: x", "params: {}");
        assertThat(route).containsEntry("isRoute", true).containsEntry("isRenderable", true)
                .containsEntry("isTestable", true)
                .containsEntry("isTemplate", false).containsEntry("sampleModel", "params: {}");

        // A batch job is testable (declarative cases) but not renderable.
        Map<String, Object> job = StudioViews.source("batch/directory-sync/job.yml", "id: j", false,
                false, "id: j", null);
        assertThat(job).containsEntry("isJob", true).containsEntry("isTestable", true)
                .containsEntry("isRoute", false).containsEntry("isRenderable", false);

        // A colocated *.sample.yml fixture (a non-method yml under web/) is not itself renderable.
        Map<String, Object> fixture = StudioViews.source("web/api/users/get.sample.yml", "x", true,
                false, "x", null);
        assertThat(fixture).containsEntry("isRoute", false).containsEntry("isRenderable", false);

        // A config yml outside web/ is not renderable.
        Map<String, Object> config = StudioViews.source("config/tesseraql.yml", "x", true, false,
                "x", null);
        assertThat(config).containsEntry("isRenderable", false);
    }

    @Test
    void renderJsonKindHasNoVisualPreview() {
        Map<String, Object> json = StudioViews.render(
                StudioService.RenderResult.ok("json", "{\n  \"a\" : 1\n}"));

        assertThat(json).containsEntry("ok", true).containsEntry("isHtml", false)
                .containsEntry("kind", "json").containsEntry("output", "{\n  \"a\" : 1\n}");
        assertThat(json.get("previewDoc")).isNull();
    }

    @Test
    void renderBuildsModelWithHighlightedTextAndIframeDoc() {
        // A bare fragment is wrapped into a standalone doc linking the hc stylesheet for the iframe.
        Map<String, Object> ok = StudioViews.render(
                StudioService.RenderResult.ok("html", "<p class=\"hc-alert\">Hi</p>"));
        assertThat(ok).containsEntry("ok", true).containsEntry("isHtml", true)
                .containsEntry("output", "<p class=\"hc-alert\">Hi</p>");
        assertThat((String) ok.get("outputHtml")).contains("hc-code__tok");
        assertThat((String) ok.get("previewDoc"))
                .startsWith("<!DOCTYPE html>")
                .contains("hypermedia-components__core/dist/hc.min.css")
                .contains("<p class=\"hc-alert\">Hi</p>");

        // A full-page render (its own <html>) is previewed verbatim, not double-wrapped.
        Map<String, Object> page = StudioViews.render(
                StudioService.RenderResult.ok("html",
                        "<!DOCTYPE html><html><body>x</body></html>"));
        assertThat((String) page.get("previewDoc"))
                .isEqualTo("<!DOCTYPE html><html><body>x</body></html>");

        // A TEXT render has no visual iframe (isHtml false, no previewDoc).
        Map<String, Object> text = StudioViews.render(
                StudioService.RenderResult.ok("text", "name: value"));
        assertThat(text).containsEntry("isHtml", false);
        assertThat(text.get("previewDoc")).isNull();

        // A failed render surfaces the error and offers no output.
        Map<String, Object> bad = StudioViews.render(
                StudioService.RenderResult.invalid("html", "boom"));
        assertThat(bad).containsEntry("ok", false).containsEntry("error", "boom");
        assertThat(bad.get("previewDoc")).isNull();
    }

    @Test
    void sourceModelCarriesDraftStateForComparison() {
        Map<String, Object> model = StudioViews.source("a.sql", "select 2 -- draft", false, true,
                "select 1 -- source", null);

        assertThat(model).containsEntry("content", "select 2 -- draft")
                .containsEntry("hasDraft", true)
                .containsEntry("sourceContent", "select 1 -- source");
    }

    @Test
    void previewBuildsModel() {
        assertThat(StudioViews.preview(PreviewResult.valid("sql", "select 1")))
                .containsEntry("valid", true)
                .containsEntry("ok", true)
                .containsEntry("needsData", false)
                .containsEntry("result", "select 1");

        assertThat(StudioViews.preview(PreviewResult.invalid("route", "boom")))
                .containsEntry("valid", false)
                .containsEntry("ok", false)
                .containsEntry("error", "boom");

        // A template that parses but needs route data is a warning, not a clean success.
        assertThat(StudioViews.preview(PreviewResult.valid("template",
                "template parses; full render needs route data (npe)")))
                .containsEntry("valid", true)
                .containsEntry("needsData", true)
                .containsEntry("ok", false);
    }

    @Test
    void sourceModelBuildsDiffAgainstSavedSource() {
        // content is the draft (new), sourceContent the on-disk source (old).
        Map<String, Object> model = StudioViews.source("a.sql", "a\nB\nc", false, true, "a\nb\nc",
                null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) model.get("diff");
        assertThat(diff).hasSize(4);
        assertThat(diff.get(0)).containsEntry("state", "context").containsEntry("oldNo", 1)
                .containsEntry("newNo", 1).containsEntry("text", "a");
        assertThat(diff.get(1)).containsEntry("state", "removed").containsEntry("oldNo", 2)
                .containsEntry("text", "b");
        assertThat(diff.get(1).get("newNo")).isNull();
        assertThat(diff.get(2)).containsEntry("state", "added").containsEntry("newNo", 2)
                .containsEntry("text", "B");
        assertThat(diff.get(2).get("oldNo")).isNull();
        assertThat(diff.get(3)).containsEntry("state", "context").containsEntry("oldNo", 3)
                .containsEntry("newNo", 3).containsEntry("text", "c");
    }

    @Test
    void sourceModelDiffsNewFileAsAllAdded() {
        // A draft of a not-yet-saved file (no source) diffs as every line added.
        Map<String, Object> model = StudioViews.source("new.sql", "x\ny", false, true, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) model.get("diff");
        assertThat(diff).hasSize(2);
        assertThat(diff).allSatisfy(line -> {
            assertThat(line).containsEntry("state", "added");
            assertThat(line.get("oldNo")).isNull();
        });
        assertThat(diff.get(0)).containsEntry("newNo", 1).containsEntry("text", "x");
        assertThat(diff.get(1)).containsEntry("newNo", 2).containsEntry("text", "y");
    }

    @Test
    void sourceModelHighlightsContentAndDiffByFileType() {
        // The read-only view carries highlighted contentHtml for the file's language.
        Map<String, Object> readOnly = StudioViews.source("web/api/users/search.sql", "select 1",
                true, false, "select 1", null);
        assertThat((String) readOnly.get("contentHtml"))
                .contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">select</span>");

        // Each diff line carries highlighted html (here the changed SQL lines get a keyword span).
        Map<String, Object> edited = StudioViews.source("q.sql", "select 2", false, true,
                "select 1", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) edited.get("diff");
        assertThat(diff).anySatisfy(line -> assertThat((String) line.get("html"))
                .contains("data-tok=\"keyword\">select</span>"));
    }
}
