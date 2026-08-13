package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The finding's wire shape is a contract, not an implementation detail: the CLI's
 * {@code lint --format json} document, the MCP dev-tools lint tool, the ops and Studio rows and
 * the VS Code extension all read {@code severity} as the string {@code "error"} or
 * {@code "warning"}. The severity became an enum inside the record
 * (docs/lint-restructure.md decision 4) and nothing outside was allowed to notice, so the
 * serialized shape is pinned here.
 */
class LintFindingWireShapeTest {

    @Test
    void serializesTheDocumentedShape() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new LintFinding("TQL-YAML-1043",
                LintFinding.Severity.WARNING, "web/get.yml", "Unknown key", 7, 3));

        assertThat(json).isEqualTo("{\"code\":\"TQL-YAML-1043\",\"severity\":\"warning\","
                + "\"source\":\"web/get.yml\",\"message\":\"Unknown key\",\"line\":7,"
                + "\"column\":3,\"error\":false}");
    }

    @Test
    void anErrorSerializesTheErrorSeverityAndFlag() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new LintFinding("TQL-YAML-1044",
                LintFinding.Severity.ERROR, "web/get.yml", "Renamed key"));

        assertThat(json).isEqualTo("{\"code\":\"TQL-YAML-1044\",\"severity\":\"error\","
                + "\"source\":\"web/get.yml\",\"message\":\"Renamed key\",\"line\":null,"
                + "\"column\":null,\"error\":true}");
    }

    /** The text surfaces (CLI and the {@code tesseraql:lint} goal) interpolate the accessor. */
    @Test
    void theSeverityAccessorStaysTheWireString() {
        assertThat(new LintFinding("X", LintFinding.Severity.WARNING, "a.yml", "m").severity())
                .isEqualTo("warning");
        assertThat(new LintFinding("X", LintFinding.Severity.ERROR, "a.yml", "m").severity())
                .isEqualTo("error");
    }
}
