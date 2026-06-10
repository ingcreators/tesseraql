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
    void sourceViewerRendersEditorFormWhenWritable() {
        String html = StudioConsole.renderSource("a.sql", "select 1", false);

        assertThat(html).doesNotContain("Read-only mode");
        assertThat(html).contains("<textarea name=\"content\"").contains("select 1");
        assertThat(html).contains("action=\"/_tesseraql/studio/ui/save\"");
        assertThat(html).contains("action=\"/_tesseraql/studio/ui/apply\"");
        assertThat(html).contains("Save draft").contains("Apply draft");
        assertThat(html).contains("name=\"path\" value=\"a.sql\"");
    }

    @Test
    void sourceViewerShowsStatusBanner() {
        String html = StudioConsole.renderSource("a.sql", "select 1", false, "Draft saved.");

        assertThat(html).contains("class=\"status\"").contains("Draft saved.");
    }

    @Test
    void readOnlySourceHasNoEditorForm() {
        String html = StudioConsole.renderSource("a.sql", "select 1", true);

        assertThat(html).doesNotContain("<textarea").doesNotContain("/ui/save");
        assertThat(html).contains("Read-only mode");
    }

    @Test
    void rendersWizardIndex() {
        String html = StudioConsole.renderWizardIndex(StudioWizards.all());

        assertThat(html).contains("Setup wizards");
        assertThat(html).contains("SAML SP").contains("SCIM provisioning")
                .contains("Identity realm mapping");
        assertThat(html).contains("/_tesseraql/studio/ui/wizard/saml");
    }

    @Test
    void rendersWizardForm() {
        String html = StudioConsole.renderWizardForm(StudioWizards.byKind("saml"));

        assertThat(html).contains("SAML SP wizard");
        assertThat(html).contains("action=\"/_tesseraql/studio/ui/wizard/saml\"");
        assertThat(html).contains("name=\"acsUrl\"").contains("name=\"publicKey\"");
        // The certificate field is a textarea; required fields are marked.
        assertThat(html).contains("<textarea name=\"publicKey\"");
        assertThat(html).contains("Generate config");
    }

    @Test
    void rendersWizardResultWithEscapedYaml() {
        String html = StudioConsole.renderWizardResult(StudioWizards.byKind("scim"),
                "tesseraql:\n  scim:\n    enabled: true\n");

        assertThat(html).contains("SCIM provisioning config");
        assertThat(html).contains("<pre class=\"source\">");
        assertThat(html).contains("tesseraql:").contains("enabled: true");
    }
}
