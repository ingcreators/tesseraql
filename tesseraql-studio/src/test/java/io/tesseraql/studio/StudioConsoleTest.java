package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.JobSummary;
import io.tesseraql.studio.StudioService.RouteSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudioConsoleTest {

    @Test
    void rendersExplorerWithRoutesAndJobs() {
        Explorer explorer = new Explorer("user-admin", false,
                List.of(new RouteSummary("users.search", "GET", "/api/users", "query-json",
                        "web/api/users/get.yml")),
                List.of(new JobSummary("nightly", "batch-pipeline", "jobs/nightly.yml")));

        String html = StudioConsole.renderExplorer(explorer);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("TesseraQL Studio").contains("user-admin");
        assertThat(html).contains("badge rw").doesNotContain("badge ro");
        assertThat(html).contains("users.search").contains("/api/users").contains("query-json");
        assertThat(html).contains("nightly").contains("batch-pipeline");
        // Source links point at the HTML source viewer with a URL-encoded path.
        assertThat(html).contains("/_tesseraql/studio/ui/source?path=web%2Fapi%2Fusers%2Fget.yml");
    }

    @Test
    void rendersReadOnlyBadgeAndEmptyStates() {
        Explorer explorer = new Explorer("empty-app", true, List.of(), List.of());

        String html = StudioConsole.renderExplorer(explorer);

        assertThat(html).contains("badge ro").doesNotContain("badge rw");
        assertThat(html).contains("No routes defined.").contains("No jobs defined.");
    }

    @Test
    void rendersSourceViewerAndEscapesContent() {
        String html = StudioConsole.renderSource("web/api/users/get.yml",
                "id: users.search\nbody: <b>x</b> & y", true);

        assertThat(html).contains("web/api/users/get.yml");
        assertThat(html).contains("Read-only mode");
        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt; &amp; y");
        assertThat(html).doesNotContain("<b>x</b>");
        assertThat(html).contains("href=\"/_tesseraql/studio/ui\"");
    }

    @Test
    void sourceViewerOmitsReadOnlyNoticeWhenWritable() {
        String html = StudioConsole.renderSource("a.sql", "select 1", false);

        assertThat(html).doesNotContain("Read-only mode");
        assertThat(html).contains("select 1");
    }
}
