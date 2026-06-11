package io.tesseraql.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoverageExportersTest {

    private static Map<String, SqlCoverageReport> reports() {
        Map<String, SqlCoverageReport> reports = new LinkedHashMap<>();
        // 2 of 3 coverable lines hit; 1 branch with one outcome seen.
        reports.put("web/api/users/search.sql", new SqlCoverageReport(
                "web/api/users/search.sql", Set.of(1, 3), Set.of(1, 2, 3), 1, 1, 0.5, 2.0 / 3));
        return reports;
    }

    @Test
    void coberturaXmlListsCoverableLinesWithHits() {
        String xml = CoberturaReporter.toXml(reports());
        assertThat(xml).contains("<coverage line-rate=\"0.6667\" branch-rate=\"0.5000\""
                + " lines-valid=\"3\" lines-covered=\"2\" branches-valid=\"2\" branches-covered=\"1\"");
        assertThat(xml).contains("timestamp=\"0\"");
        assertThat(xml).contains("filename=\"web/api/users/search.sql\"");
        assertThat(xml).contains("<line number=\"1\" hits=\"1\" branch=\"false\"/>");
        assertThat(xml).contains("<line number=\"2\" hits=\"0\" branch=\"false\"/>");
        assertThat(xml).contains("<line number=\"3\" hits=\"1\" branch=\"false\"/>");
    }

    @Test
    void coberturaXmlReportsFullRatesForAnEmptyRun() {
        String xml = CoberturaReporter.toXml(Map.of());
        assertThat(xml).contains("line-rate=\"1.0000\"").contains("branch-rate=\"1.0000\"");
    }

    @Test
    void sonarQubeGenericCoverageMarksEachCoverableLine() {
        String xml = SonarQubeReporter.toXml(reports());
        assertThat(xml).contains("<coverage version=\"1\">");
        assertThat(xml).contains("<file path=\"web/api/users/search.sql\">");
        assertThat(xml).contains("<lineToCover lineNumber=\"1\" covered=\"true\"/>");
        assertThat(xml).contains("<lineToCover lineNumber=\"2\" covered=\"false\"/>");
        assertThat(xml).contains("<lineToCover lineNumber=\"3\" covered=\"true\"/>");
    }

    @Test
    void allureResultsAreOneDeterministicFilePerCase() {
        TestReport report = new TestReport(List.of(
                TestResult.pass("search finds sato"),
                TestResult.fail("rowCount check", "expected rowCount 1 but was 0")));

        Map<String, String> first = AllureReporter.toResults(report, "tesseraql");
        Map<String, String> second = AllureReporter.toResults(report, "tesseraql");
        assertThat(first).hasSize(2).isEqualTo(second);

        first.forEach((file, json) -> assertThat(file).endsWith("-result.json"));
        String passed = first.values().stream().filter(json -> json.contains("search finds sato"))
                .findFirst().orElseThrow();
        assertThat(passed).contains("\"status\" : \"passed\"")
                .contains("\"stage\" : \"finished\"")
                .contains("\"value\" : \"tesseraql\"");
        String failed = first.values().stream().filter(json -> json.contains("rowCount check"))
                .findFirst().orElseThrow();
        assertThat(failed).contains("\"status\" : \"failed\"")
                .contains("expected rowCount 1 but was 0");
    }
}
