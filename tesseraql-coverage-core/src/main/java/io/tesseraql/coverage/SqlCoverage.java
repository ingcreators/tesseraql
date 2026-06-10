package io.tesseraql.coverage;

import io.tesseraql.core.sql.CoverageTrace;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Accumulates {@link CoverageTrace}s produced while rendering 2-way SQL and computes line and
 * branch coverage per SQL file (design ch. 14). A branch is fully covered when both its taken and
 * not-taken outcomes have been observed across renders.
 */
public final class SqlCoverage {

    private final Map<String, FileCoverage> byFile = new LinkedHashMap<>();

    /** Records a render's coverage trace against a SQL file id (line denominator unknown). */
    public void record(String sqlId, CoverageTrace trace) {
        record(sqlId, trace, Set.of());
    }

    /**
     * Records a render's coverage trace along with the file's coverable lines (from
     * {@link SqlCoverableLines}), so line coverage can be computed against a real denominator.
     */
    public void record(String sqlId, CoverageTrace trace, Set<Integer> coverableLines) {
        FileCoverage coverage = byFile.computeIfAbsent(sqlId, key -> new FileCoverage());
        coverage.coveredLines.addAll(trace.coveredLines());
        coverage.coverableLines.addAll(coverableLines);
        for (CoverageTrace.Branch branch : trace.branches()) {
            coverage.branchOutcomes.computeIfAbsent(branch.sourceLine(), key -> new HashSet<>())
                    .add(branch.taken());
        }
    }

    /** Returns the coverage report for a SQL file, or {@code null} if none was recorded. */
    public SqlCoverageReport report(String sqlId) {
        FileCoverage coverage = byFile.get(sqlId);
        return coverage == null ? null : coverage.toReport(sqlId);
    }

    /** Returns reports for every recorded SQL file. */
    public Map<String, SqlCoverageReport> reports() {
        Map<String, SqlCoverageReport> reports = new LinkedHashMap<>();
        byFile.forEach((sqlId, coverage) -> reports.put(sqlId, coverage.toReport(sqlId)));
        return reports;
    }

    private static final class FileCoverage {
        private final Set<Integer> coveredLines = new TreeSet<>();
        private final Set<Integer> coverableLines = new TreeSet<>();
        private final Map<Integer, Set<Boolean>> branchOutcomes = new LinkedHashMap<>();

        SqlCoverageReport toReport(String sqlId) {
            int branchCount = branchOutcomes.size();
            int outcomes = branchOutcomes.values().stream().mapToInt(Set::size).sum();
            double branchRatio = branchCount == 0 ? 1.0 : outcomes / (2.0 * branchCount);

            int coverableCount = coverableLines.size();
            long hitCoverable = coverableLines.stream().filter(coveredLines::contains).count();
            double lineRatio = coverableCount == 0 ? 1.0 : (double) hitCoverable / coverableCount;

            return new SqlCoverageReport(sqlId, Set.copyOf(coveredLines), coverableCount,
                    branchCount, outcomes, branchRatio, lineRatio);
        }
    }
}
