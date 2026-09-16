package io.tesseraql.coverage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Evaluates accumulated coverage against {@link CoverageThresholds} (design ch. 14). Used by the
 * build to fail when coverage drops below the configured minimum.
 *
 * <p>The SQL population is every file the run declared (the manifest's bindings) or recorded, so
 * a bound file no case touches fails its line threshold instead of sitting outside the gate. A
 * threshold naming a kind the run did not measure is a violation too: the resolver used to drop
 * a key it did not know without a word, so {@code queue-consume: 100} and a typo alike passed
 * (docs/audit-low-leads.md G16, G22).
 */
public final class CoverageGate {

    private CoverageGate() {
    }

    /** Checks every declared or recorded SQL file's line and branch coverage against the thresholds. */
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
        java.util.Set<String> measured = new java.util.TreeSet<>();
        for (ItemCoverage kind : kinds) {
            measured.add(kind.kind());
            double required = thresholds.kindThreshold(kind.kind());
            if (kind.ratio() < required) {
                violations.add(String.format("%s coverage %.0f%% (%d/%d) < required %.0f%%",
                        kind.kind(), kind.ratio() * 100, kind.covered().size(),
                        kind.declared().size(), required * 100));
            }
        }
        for (String kind : new java.util.TreeSet<>(thresholds.kinds().keySet())) {
            if (!measured.contains(kind)) {
                violations.add("coverage.thresholds." + kind + " names a kind this run did not"
                        + " measure — the kinds are " + measured);
            }
        }
        return new Result(violations.isEmpty(), List.copyOf(violations));
    }

    /** Gate outcome. */
    public record Result(boolean passed, List<String> violations) {
    }
}
