package io.tesseraql.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates accumulated coverage against {@link CoverageThresholds} (design ch. 14). Used by the
 * build to fail when coverage drops below the configured minimum.
 */
public final class CoverageGate {

    private CoverageGate() {
    }

    /** Checks every recorded SQL file's line and branch coverage against the thresholds. */
    public static Result check(SqlCoverage coverage, CoverageThresholds thresholds) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, SqlCoverageReport> entry : coverage.reports().entrySet()) {
            SqlCoverageReport report = entry.getValue();
            if (report.lineRatio() < thresholds.sqlLine()) {
                violations.add(String.format("%s: line coverage %.0f%% < required %.0f%%",
                        entry.getKey(), report.lineRatio() * 100, thresholds.sqlLine() * 100));
            }
            if (report.branchRatio() < thresholds.sqlBranch()) {
                violations.add(String.format("%s: branch coverage %.0f%% < required %.0f%%",
                        entry.getKey(), report.branchRatio() * 100, thresholds.sqlBranch() * 100));
            }
        }
        return new Result(violations.isEmpty(), List.copyOf(violations));
    }

    /** Gate outcome. */
    public record Result(boolean passed, List<String> violations) {
    }
}
