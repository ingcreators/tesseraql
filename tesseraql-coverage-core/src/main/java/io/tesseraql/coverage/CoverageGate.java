package io.tesseraql.coverage;

import java.util.ArrayList;
import java.util.Collection;
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
        return check(coverage, List.of(), thresholds);
    }

    /**
     * Checks SQL line/branch coverage per file plus each item-coverage kind's covered-of-declared
     * ratio against its configured threshold.
     */
    public static Result check(SqlCoverage coverage, Collection<ItemCoverage> kinds,
            CoverageThresholds thresholds) {
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
        for (ItemCoverage kind : kinds) {
            double required = thresholds.kindThreshold(kind.kind());
            if (kind.ratio() < required) {
                violations.add(String.format("%s coverage %.0f%% (%d/%d) < required %.0f%%",
                        kind.kind(), kind.ratio() * 100, kind.covered().size(),
                        kind.declared().size(), required * 100));
            }
        }
        return new Result(violations.isEmpty(), List.copyOf(violations));
    }

    /** Gate outcome. */
    public record Result(boolean passed, List<String> violations) {
    }
}
