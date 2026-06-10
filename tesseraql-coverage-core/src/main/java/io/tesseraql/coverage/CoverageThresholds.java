package io.tesseraql.coverage;

/**
 * Coverage gate thresholds as ratios in {@code [0,1]} (design ch. 5.2 {@code coverage.thresholds}).
 * Gates on SQL line and branch coverage; route/assertion thresholds follow.
 *
 * @param sqlLine   minimum acceptable SQL line-coverage ratio per file
 * @param sqlBranch minimum acceptable SQL branch-coverage ratio per file
 */
public record CoverageThresholds(double sqlLine, double sqlBranch) {

    /** Branch-only thresholds from a percentage (e.g. 80 -&gt; 0.80); no line gate. */
    public static CoverageThresholds ofPercent(double sqlBranchPercent) {
        return ofPercent(0.0, sqlBranchPercent);
    }

    /** Line and branch thresholds from percentages (e.g. 80 -&gt; 0.80). */
    public static CoverageThresholds ofPercent(double sqlLinePercent, double sqlBranchPercent) {
        return new CoverageThresholds(sqlLinePercent / 100.0, sqlBranchPercent / 100.0);
    }
}
