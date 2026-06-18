package io.tesseraql.report.docs;

import io.tesseraql.coverage.CoverageRegression;
import java.util.List;

/**
 * The coverage-regression gate at the report layer (Studio backlog category 3): compares a run's
 * aggregate SQL coverage to the previous run's — the most recent entry of the {@code history.json}
 * ring captured <em>before</em> this run is appended — and reports a regression when it drops by
 * more than a tolerance. With no prior run there is no baseline, so it passes. A thin adapter over
 * the pure {@link CoverageRegression} that knows the {@link ReportDoc}/{@link ReportHistory} shapes;
 * the {@code report} goal and {@code tesseraql test --report} opt in to failing on its verdict.
 */
public final class ReportRegression {

    private ReportRegression() {
    }

    /**
     * Checks {@code current} against the most recent entry of {@code priorHistory} (the runs recorded
     * before this one), allowing a drop of up to {@code tolerancePercent} percentage points. Passes
     * with an empty history (no baseline yet).
     *
     * @param priorHistory    the history entries that existed before this run, oldest first
     * @param current         the report just generated for this run
     * @param tolerancePercent the allowed coverage drop, in percentage points (negative treated as 0)
     */
    public static CoverageRegression.Result check(List<ReportHistory.Entry> priorHistory,
            ReportDoc current, double tolerancePercent) {
        if (priorHistory.isEmpty()) {
            return new CoverageRegression.Result(true, List.of());
        }
        ReportHistory.Entry previous = priorHistory.get(priorHistory.size() - 1);
        ReportDoc.Summary summary = current.summary();
        return CoverageRegression.check(summary.sqlLineRatio(), summary.sqlBranchRatio(),
                previous.sqlLineRatio(), previous.sqlBranchRatio(),
                Math.max(0.0, tolerancePercent) / 100.0);
    }
}
