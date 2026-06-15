package io.tesseraql.report.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.coverage.CoverageGate;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.test.CrossReferenceIndex;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.test.TestSuite;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.test.TestSuiteLoader;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.SqlBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the report-layer overlay ({@link ReportDoc} / {@code report.json}, documentation portal v2)
 * from a finished test run. It joins each manifest route to the run's results and coverage:
 *
 * <ul>
 *   <li>route &rarr; test cases via the shared {@link CrossReferenceIndex} (the same linkage the
 *       spec layer uses), then case &rarr; pass/fail by name from the run's {@link TestReport};</li>
 *   <li>route &rarr; SQL coverage by resolving each bound SQL file to its app-home-relative key (as
 *       the runtime records coverage) and looking it up in the collected {@code SqlCoverage};</li>
 *   <li>route &rarr; item coverage (validation, notification, ...) restricted to the items that name
 *       the route.</li>
 * </ul>
 *
 * <p>It re-reads the {@code tests/} suites statically (no database) to rebuild the cross-reference;
 * the run itself is performed elsewhere ({@link AppTestRunner}). The output is run-dependent (it
 * carries a {@code runId}/{@code generatedAt}) and is the overlay sidecar, not the byte-stable
 * {@code spec.json}. Errors are raised in the {@link TqlDomain#REPORT} domain.
 */
public final class ReportGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2005);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TestSuiteLoader suiteLoader = new TestSuiteLoader();

    /**
     * Builds the overlay model from a finished run.
     *
     * @param manifest    the application manifest (routes, app home)
     * @param result      the finished run's report, SQL coverage, and item-coverage kinds
     * @param thresholds  the coverage gate thresholds in effect
     * @param runId       a stable run identity (CI build id, or pass {@code generatedAt})
     * @param generatedAt the ISO-8601 instant stamped on the report
     */
    public ReportDoc generate(AppManifest manifest, AppTestRunner.RunResult result,
            CoverageThresholds thresholds, String runId, String generatedAt) {
        List<TestSuite> suites = loadSuites(manifest.appHome());
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        Map<String, TestResult> resultsByName = resultsByName(result.report());
        Map<String, SqlCoverageReport> sqlReports = result.coverage().reports();
        ItemCoverage routeKind = result.kind("route");
        Path appHome = manifest.appHome();

        Map<String, ReportDoc.RouteReport> routes = new LinkedHashMap<>();
        for (RouteFile route : manifest.routes()) {
            String id = route.definition().id();
            if (id == null) {
                continue;
            }
            boolean covered = routeKind != null && routeKind.covered().contains(id);
            routes.put(id, new ReportDoc.RouteReport(covered,
                    routeTests(index, route, resultsByName),
                    routeSql(appHome, route, sqlReports),
                    routeItemCoverage(id, result.kinds())));
        }

        List<ReportDoc.KindCoverage> kinds = new ArrayList<>();
        for (ItemCoverage kind : result.kinds()) {
            kinds.add(new ReportDoc.KindCoverage(kind.kind(), kind.ratio(),
                    kind.covered().size(), kind.declared().size(),
                    new ArrayList<>(kind.uncovered())));
        }

        CoverageGate.Result gate = CoverageGate.check(result.coverage(), result.kinds(),
                thresholds);
        ReportDoc.Gate gateModel = new ReportDoc.Gate(gate.passed(), gate.violations());
        ReportDoc.Summary summary = summary(result.report(), sqlReports, gate.passed());
        ReportDoc.Thresholds thresholdsModel = new ReportDoc.Thresholds(
                thresholds.sqlLine(), thresholds.sqlBranch(), thresholds.kinds());

        return new ReportDoc(ReportDoc.SCHEMA_VERSION, runId, generatedAt, summary, thresholdsModel,
                gateModel, kinds, routes);
    }

    /** Serializes the overlay model as pretty JSON (the run-dependent sidecar, not byte-stable). */
    public String toJson(AppManifest manifest, AppTestRunner.RunResult result,
            CoverageThresholds thresholds, String runId, String generatedAt) {
        return toJson(generate(manifest, result, thresholds, runId, generatedAt));
    }

    /** Serializes an already-built overlay model as pretty JSON. */
    public String toJson(ReportDoc report) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            throw new TqlException(GEN_ERROR,
                    "Failed to serialize report.json: " + ex.getMessage());
        }
    }

    private static List<ReportDoc.CaseResult> routeTests(CrossReferenceIndex index, RouteFile route,
            Map<String, TestResult> resultsByName) {
        List<ReportDoc.CaseResult> cases = new ArrayList<>();
        for (TestCase test : index.casesFor(route)) {
            TestResult result = resultsByName.get(test.name());
            cases.add(result != null
                    ? new ReportDoc.CaseResult(result.name(), result.passed(), result.message())
                    : new ReportDoc.CaseResult(test.name(), false, "not run"));
        }
        return cases;
    }

    private static List<ReportDoc.SqlFileCoverage> routeSql(Path appHome, RouteFile route,
            Map<String, SqlCoverageReport> sqlReports) {
        Path routeDir = route.source().getParent();
        Set<String> keys = new LinkedHashSet<>();
        for (SqlBinding binding : CrossReferenceIndex.bindings(route.definition())) {
            if (binding.file() != null) {
                keys.add(appHome.relativize(routeDir.resolve(binding.file()).normalize())
                        .toString().replace('\\', '/'));
            }
        }
        List<ReportDoc.SqlFileCoverage> sql = new ArrayList<>();
        for (String key : keys) {
            SqlCoverageReport report = sqlReports.get(key);
            if (report != null) {
                sql.add(new ReportDoc.SqlFileCoverage(key, report.lineRatio(), report.branchRatio(),
                        report.branchCount(), report.branchOutcomes(),
                        new ArrayList<>(report.coveredLines()),
                        new ArrayList<>(report.coverableLines())));
            }
        }
        return sql;
    }

    /** Per-kind covered-of-declared ratio restricted to items named by (or prefixed with) the route. */
    private static Map<String, Double> routeItemCoverage(String routeId, List<ItemCoverage> kinds) {
        String prefix = routeId + ".";
        Map<String, Double> byKind = new LinkedHashMap<>();
        for (ItemCoverage kind : kinds) {
            if (kind.kind().equals("route")) {
                continue;
            }
            long declared = kind.declared().stream().filter(item -> names(item, routeId, prefix))
                    .count();
            if (declared == 0) {
                continue;
            }
            long covered = kind.covered().stream().filter(item -> names(item, routeId, prefix))
                    .count();
            byKind.put(kind.kind(), (double) covered / declared);
        }
        return byKind;
    }

    private static boolean names(String item, String routeId, String prefix) {
        return item.equals(routeId) || item.startsWith(prefix);
    }

    private static ReportDoc.Summary summary(TestReport report,
            Map<String, SqlCoverageReport> sqlReports, boolean gatePassed) {
        long coverable = 0;
        long hit = 0;
        long branches = 0;
        long outcomes = 0;
        for (SqlCoverageReport file : sqlReports.values()) {
            coverable += file.coverableLines().size();
            hit += file.coverableLines().stream().filter(file.coveredLines()::contains).count();
            branches += file.branchCount();
            outcomes += file.branchOutcomes();
        }
        double lineRatio = coverable == 0 ? 1.0 : (double) hit / coverable;
        double branchRatio = branches == 0 ? 1.0 : outcomes / (2.0 * branches);
        return new ReportDoc.Summary(report.results().size(), report.passed(), report.failed(),
                lineRatio, branchRatio, gatePassed);
    }

    private static Map<String, TestResult> resultsByName(TestReport report) {
        Map<String, TestResult> byName = new LinkedHashMap<>();
        for (TestResult result : report.results()) {
            byName.putIfAbsent(result.name(), result);
        }
        return byName;
    }

    /** Loads every {@code tests/**}{@code /*.yml} suite statically, sorted for stable cross-ref order. */
    private List<TestSuite> loadSuites(Path appHome) {
        Path testsDir = appHome.resolve("tests");
        if (!Files.isDirectory(testsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(testsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .map(suiteLoader::load)
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
