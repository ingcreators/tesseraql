package io.tesseraql.report.docs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report-layer overlay model for an application (documentation portal v2): the last test run's
 * results and SQL/item coverage, joined to the routes the spec layer already documents. It is the
 * run-dependent counterpart to the deterministic {@link DocModel} ({@code spec.json}); unlike the
 * spec, it carries a {@code runId}/{@code generatedAt} and is written as an optional sidecar
 * ({@code report.json}) that is never packed into the reproducible {@code .tqlapp}.
 *
 * <p>It is keyed by {@code RouteSpec.id} so the runtime can overlay {@code routes.get(id)} onto the
 * spec's route entry with no key-normalization guesswork; the route&rarr;test&rarr;result and
 * route&rarr;SQL joins are performed once, at generation time, by {@link ReportGenerator}.
 *
 * @param schemaVersion the overlay schema version, for forward compatibility
 * @param runId         a stable identity for the run (CI build id, or {@code generatedAt} by default)
 * @param generatedAt   ISO-8601 instant the report was produced
 * @param summary       aggregate totals across the whole run
 * @param thresholds    the coverage gate thresholds in effect
 * @param gate          the coverage gate verdict
 * @param kinds         every item-coverage kind (route, security, validation, ...), in runner order
 * @param routes        per-route results and coverage, keyed by route id, in manifest order
 */
public record ReportDoc(
        int schemaVersion,
        String runId,
        String generatedAt,
        Summary summary,
        Thresholds thresholds,
        Gate gate,
        List<KindCoverage> kinds,
        Map<String, RouteReport> routes) {

    /** The current overlay schema version. */
    public static final int SCHEMA_VERSION = 1;

    public ReportDoc {
        kinds = List.copyOf(kinds);
        // LinkedHashMap copy, not Map.copyOf, to keep manifest route order in the JSON.
        routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
    }

    /**
     * Aggregate totals for the run.
     *
     * @param total          number of declarative test cases
     * @param passed         cases that passed
     * @param failed         cases that failed
     * @param sqlLineRatio   covered-of-coverable SQL lines across every file in {@code [0,1]}
     * @param sqlBranchRatio observed-of-possible SQL branch outcomes across every file in {@code [0,1]}
     * @param gatePassed     whether the coverage gate passed
     */
    public record Summary(int total, long passed, long failed, double sqlLineRatio,
            double sqlBranchRatio, boolean gatePassed) {
    }

    /**
     * The coverage gate thresholds in effect (ratios in {@code [0,1]}).
     *
     * @param sqlLine   minimum SQL line-coverage ratio per file
     * @param sqlBranch minimum SQL branch-coverage ratio per file
     * @param kinds     minimum ratio per gated item-coverage kind
     */
    public record Thresholds(double sqlLine, double sqlBranch, Map<String, Double> kinds) {

        public Thresholds {
            kinds = Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
        }
    }

    /**
     * The coverage gate verdict.
     *
     * @param passed   whether every threshold was met
     * @param failures human-readable threshold violations, empty when {@code passed}
     */
    public record Gate(boolean passed, List<String> failures) {

        public Gate {
            failures = List.copyOf(failures);
        }
    }

    /**
     * One item-coverage kind's covered-of-declared standing.
     *
     * @param kind      the kind name (route, security, validation, notification, ...)
     * @param ratio     covered-of-declared ratio in {@code [0,1]}
     * @param covered   number of declared items that were exercised
     * @param declared  number of items that could be exercised
     * @param uncovered the declared-but-never-covered item ids, sorted
     */
    public record KindCoverage(String kind, double ratio, int covered, int declared,
            List<String> uncovered) {

        public KindCoverage {
            uncovered = List.copyOf(uncovered);
        }
    }

    /**
     * One route's overlay: whether the suite exercised it, its covering test cases with their
     * pass/fail results, the per-file SQL coverage of its bound SQL, and the per-kind item coverage
     * that names it.
     *
     * @param covered      whether the route's id is in the {@code route} coverage kind's covered set
     * @param tests        the covering test cases joined to this run's results, in cross-ref order
     * @param sql          SQL coverage for each of the route's bound SQL files
     * @param itemCoverage per-kind covered-of-declared ratio restricted to items naming this route
     */
    public record RouteReport(boolean covered, List<CaseResult> tests, List<SqlFileCoverage> sql,
            Map<String, Double> itemCoverage) {

        public RouteReport {
            tests = List.copyOf(tests);
            sql = List.copyOf(sql);
            itemCoverage = Collections.unmodifiableMap(new LinkedHashMap<>(itemCoverage));
        }
    }

    /**
     * A declarative test case's result, joined by name from this run.
     *
     * @param name    the case name
     * @param passed  whether it passed
     * @param message {@code OK} or the failure description
     */
    public record CaseResult(String name, boolean passed, String message) {
    }

    /**
     * Line and branch coverage of one SQL file, keeping the covered/coverable line numbers so the
     * portal can highlight the source line-by-line (the build's {@code sql-coverage.json} keeps only
     * the counts).
     *
     * @param file           the app-home-relative SQL file path
     * @param lineRatio       covered-of-coverable line ratio in {@code [0,1]}
     * @param branchRatio     observed-of-possible branch-outcome ratio in {@code [0,1]}
     * @param branchCount     number of conditional branches in the file
     * @param branchOutcomes  number of branch outcomes observed (at most {@code 2 * branchCount})
     * @param coveredLines    1-based source lines emitted at least once, sorted
     * @param coverableLines  1-based source lines the template could emit, sorted
     */
    public record SqlFileCoverage(String file, double lineRatio, double branchRatio,
            int branchCount, int branchOutcomes, List<Integer> coveredLines,
            List<Integer> coverableLines) {

        public SqlFileCoverage {
            coveredLines = List.copyOf(coveredLines);
            coverableLines = List.copyOf(coverableLines);
        }
    }
}
