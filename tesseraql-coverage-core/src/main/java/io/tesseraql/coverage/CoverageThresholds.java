package io.tesseraql.coverage;

import java.util.Map;

/**
 * Coverage gate thresholds as ratios in {@code [0,1]} (design ch. 5.2 {@code coverage.thresholds}).
 * Gates on SQL line and branch coverage plus any {@link ItemCoverage} kind (route, security,
 * assertion, iam-contract, saml, scim) by name.
 *
 * @param sqlLine   minimum acceptable SQL line-coverage ratio per file
 * @param sqlBranch minimum acceptable SQL branch-coverage ratio per file
 * @param kinds     minimum acceptable covered-of-declared ratio per coverage kind; absent kinds
 *                  are not gated
 */
public record CoverageThresholds(double sqlLine, double sqlBranch, Map<String, Double> kinds) {

    public CoverageThresholds {
        kinds = kinds == null ? Map.of() : Map.copyOf(kinds);
    }

    /** SQL-only thresholds with no item-kind gates. */
    public CoverageThresholds(double sqlLine, double sqlBranch) {
        this(sqlLine, sqlBranch, Map.of());
    }

    /** Branch-only thresholds from a percentage (e.g. 80 -&gt; 0.80); no line gate. */
    public static CoverageThresholds ofPercent(double sqlBranchPercent) {
        return ofPercent(0.0, sqlBranchPercent);
    }

    /** Line and branch thresholds from percentages (e.g. 80 -&gt; 0.80). */
    public static CoverageThresholds ofPercent(double sqlLinePercent, double sqlBranchPercent) {
        return new CoverageThresholds(sqlLinePercent / 100.0, sqlBranchPercent / 100.0);
    }

    /** The minimum ratio for an item-coverage kind, or 0.0 when the kind is not gated. */
    public double kindThreshold(String kind) {
        return kinds.getOrDefault(kind, 0.0);
    }
}
