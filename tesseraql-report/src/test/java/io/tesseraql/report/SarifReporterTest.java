package io.tesseraql.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SarifReporterTest {

    @Test
    void rendersValidSarifWithLocationsAndRules() throws Exception {
        List<SarifReporter.Finding> findings = List.of(
                new SarifReporter.Finding("sql-branch-coverage", "warning",
                        "Branch coverage 50% for a.sql", "a.sql", null),
                new SarifReporter.Finding("sql-line-coverage", "warning",
                        "Line coverage 80% for a.sql", "a.sql", 12),
                new SarifReporter.Finding("iam-contract-coverage", "note",
                        "iam-contract not covered: create-user", null, null));

        JsonNode root = new ObjectMapper().readTree(SarifReporter.toSarif("tesseraql", findings));
        assertThat(root.get("version").asText()).isEqualTo("2.1.0");

        JsonNode run = root.get("runs").get(0);
        assertThat(run.get("tool").get("driver").get("name").asText()).isEqualTo("tesseraql");
        assertThat(run.get("tool").get("driver").get("rules")).hasSize(3);
        assertThat(run.get("results")).hasSize(3);

        JsonNode first = run.get("results").get(0);
        assertThat(first.get("ruleId").asText()).isEqualTo("sql-branch-coverage");
        assertThat(first.get("level").asText()).isEqualTo("warning");
        assertThat(first.get("message").get("text").asText()).contains("Branch coverage");
        assertThat(first.get("locations").get(0).get("physicalLocation")
                .get("artifactLocation").get("uri").asText()).isEqualTo("a.sql");

        assertThat(run.get("results").get(1).get("locations").get(0).get("physicalLocation")
                .get("region").get("startLine").asInt()).isEqualTo(12);
        assertThat(run.get("results").get(2).has("locations")).isFalse();
    }

    @Test
    void deduplicatesRuleIds() throws Exception {
        List<SarifReporter.Finding> findings = List.of(
                new SarifReporter.Finding("r", "warning", "one", null, null),
                new SarifReporter.Finding("r", "warning", "two", null, null));
        JsonNode root = new ObjectMapper().readTree(SarifReporter.toSarif("t", findings));
        assertThat(root.get("runs").get(0).get("tool").get("driver").get("rules")).hasSize(1);
        assertThat(root.get("runs").get(0).get("results")).hasSize(2);
    }
}
