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
    }

    @Test
    void sourceBuildsModel() {
        Map<String, Object> model = StudioViews.source("a.sql", "select 1", false, false,
                "select 1");

        assertThat(model).containsEntry("path", "a.sql")
                .containsEntry("content", "select 1")
                .containsEntry("editable", true)
                .containsEntry("readOnly", false)
                .containsEntry("hasDraft", false)
                .containsEntry("sourceContent", "select 1");
    }

    @Test
    void sourceModelCarriesDraftStateForComparison() {
        Map<String, Object> model = StudioViews.source("a.sql", "select 2 -- draft", false, true,
                "select 1 -- source");

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
        Map<String, Object> model = StudioViews.source("a.sql", "a\nB\nc", false, true, "a\nb\nc");

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
        Map<String, Object> model = StudioViews.source("new.sql", "x\ny", false, true, null);

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
                true, false, "select 1");
        assertThat((String) readOnly.get("contentHtml"))
                .contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">select</span>");

        // Each diff line carries highlighted html (here the changed SQL lines get a keyword span).
        Map<String, Object> edited = StudioViews.source("q.sql", "select 2", false, true,
                "select 1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) edited.get("diff");
        assertThat(diff).anySatisfy(line -> assertThat((String) line.get("html"))
                .contains("data-tok=\"keyword\">select</span>"));
    }
}
