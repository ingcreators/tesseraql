package io.tesseraql.report.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.coverage.CoverageRegression;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportRegressionTest {

    private static ReportDoc report(double lineRatio, double branchRatio) {
        return new ReportDoc(ReportDoc.SCHEMA_VERSION, "run", "2026-06-18T12:00:00Z",
                new ReportDoc.Summary(10, 10, 0, lineRatio, branchRatio, true),
                new ReportDoc.Thresholds(0.0, 0.0, Map.of()), new ReportDoc.Gate(true, List.of()),
                List.of(), Map.of());
    }

    private static ReportHistory.Entry entry(String runId, double lineRatio, double branchRatio) {
        return new ReportHistory.Entry(runId, "2026-06-17T12:00:00Z", 10, 10, 0, lineRatio,
                branchRatio, true);
    }

    @Test
    void withNoPriorRunThereIsNoBaselineSoItPasses() {
        assertThat(ReportRegression.check(List.of(), report(0.50, 0.50), 0.0).passed()).isTrue();
    }

    @Test
    void aDropAgainstThePreviousRunIsARegression() {
        CoverageRegression.Result result = ReportRegression.check(
                List.of(entry("prev", 0.90, 0.80)), report(0.80, 0.80), 0.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("line");
    }

    @Test
    void itComparesAgainstTheMostRecentEntryNotTheOldest() {
        // Oldest run had low coverage; the immediately-previous run had high coverage — compare to it.
        List<ReportHistory.Entry> history = List.of(entry("old", 0.50, 0.50),
                entry("prev", 0.90, 0.90));

        assertThat(ReportRegression.check(history, report(0.80, 0.80), 0.0).passed()).isFalse();
        // Holding the previous run's level passes.
        assertThat(ReportRegression.check(history, report(0.90, 0.90), 0.0).passed()).isTrue();
    }

    @Test
    void theToleranceIsAppliedInPercentagePoints() {
        List<ReportHistory.Entry> history = List.of(entry("prev", 0.90, 0.90));
        // 90% -> 89% within a 2-point tolerance: holds.
        assertThat(ReportRegression.check(history, report(0.89, 0.90), 2.0).passed()).isTrue();
        // 90% -> 87% exceeds the 2-point tolerance: a regression.
        assertThat(ReportRegression.check(history, report(0.87, 0.90), 2.0).passed()).isFalse();
    }
}
