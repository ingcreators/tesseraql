package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.JobSummary;
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
        Map<String, Object> model = StudioViews.source("a.sql", "select 1", false);

        assertThat(model).containsEntry("path", "a.sql")
                .containsEntry("content", "select 1")
                .containsEntry("editable", true)
                .containsEntry("readOnly", false);
    }
}
