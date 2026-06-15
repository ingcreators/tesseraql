package io.tesseraql.studio;

import java.util.List;
import java.util.Map;

/**
 * The studio-side mirror of the build's {@code report.json} (documentation portal v2 report layer),
 * read back from the app home's reserved {@code .tesseraql/docs/} namespace. It is the run-dependent
 * counterpart to {@link DocService.DocSpec}: the last test run's results and SQL/item coverage,
 * keyed by route id so the portal can overlay {@link #routeReport(String)} onto a spec route entry.
 *
 * <p>Like {@code DocSpec}, this mirrors only the JSON shape the build emits (unknown fields are
 * ignored on read); the build-side model lives in {@code tesseraql-report}, which Studio does not
 * depend on. Every collection is null-tolerant so a partial or forward-compatible report still
 * deserializes.
 */
public record ReportOverlay(
        int schemaVersion,
        String runId,
        String generatedAt,
        Summary summary,
        Thresholds thresholds,
        Gate gate,
        List<KindCoverage> kinds,
        Map<String, RouteReport> routes) {

    public ReportOverlay {
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        routes = routes == null ? Map.of() : Map.copyOf(routes);
    }

    /** The overlay for one route id, or {@code null} when the run recorded none. */
    public RouteReport routeReport(String id) {
        return id == null ? null : routes.get(id);
    }

    /** Aggregate run totals. */
    public record Summary(int total, long passed, long failed, double sqlLineRatio,
            double sqlBranchRatio, boolean gatePassed) {
    }

    /** The coverage gate thresholds in effect. */
    public record Thresholds(double sqlLine, double sqlBranch, Map<String, Double> kinds) {

        public Thresholds {
            kinds = kinds == null ? Map.of() : Map.copyOf(kinds);
        }
    }

    /** The coverage gate verdict. */
    public record Gate(boolean passed, List<String> failures) {

        public Gate {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    /** One item-coverage kind's standing. */
    public record KindCoverage(String kind, double ratio, int covered, int declared,
            List<String> uncovered) {

        public KindCoverage {
            uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
        }
    }

    /** One route's overlay: whether it was exercised, its test results, and its SQL coverage. */
    public record RouteReport(boolean covered, List<CaseResult> tests, List<SqlFileCoverage> sql,
            Map<String, Double> itemCoverage) {

        public RouteReport {
            tests = tests == null ? List.of() : List.copyOf(tests);
            sql = sql == null ? List.of() : List.copyOf(sql);
            itemCoverage = itemCoverage == null ? Map.of() : Map.copyOf(itemCoverage);
        }
    }

    /** A test case's result, joined by name. */
    public record CaseResult(String name, boolean passed, String message) {
    }

    /** Line and branch coverage of one SQL file, with the covered/coverable line numbers. */
    public record SqlFileCoverage(String file, double lineRatio, double branchRatio,
            int branchCount, int branchOutcomes, List<Integer> coveredLines,
            List<Integer> coverableLines) {

        public SqlFileCoverage {
            coveredLines = coveredLines == null ? List.of() : List.copyOf(coveredLines);
            coverableLines = coverableLines == null ? List.of() : List.copyOf(coverableLines);
        }
    }
}
