package io.tesseraql.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportersTest {

    private static TestReport report() {
        return new TestReport(List.of(
                TestResult.pass("search finds sato"),
                TestResult.fail("rowCount check", "expected rowCount 1 but was 0")));
    }

    @Test
    void junitXmlContainsCountsAndFailure() {
        String xml = JUnitXmlReporter.toXml(report(), "users-suite");
        assertThat(xml).contains("tests=\"2\"").contains("failures=\"1\"");
        assertThat(xml).contains("<testcase name=\"search finds sato\"/>");
        assertThat(xml).contains("<failure message=\"expected rowCount 1 but was 0\"/>");
    }

    @Test
    void junitXmlEscapesSpecialCharacters() {
        TestReport report = new TestReport(List.of(TestResult.fail("a<b&c", "x>\"y\"")));
        String xml = JUnitXmlReporter.toXml(report, "s");
        assertThat(xml).contains("a&lt;b&amp;c").contains("x&gt;&quot;y&quot;");
    }

    @Test
    void jsonContainsCountsAndCases() {
        String json = JsonReporter.toJson(report());
        assertThat(json).contains("\"total\" : 2").contains("\"passed\" : 1").contains("\"failed\" : 1");
        assertThat(json).contains("search finds sato").contains("rowCount check");
    }
}
