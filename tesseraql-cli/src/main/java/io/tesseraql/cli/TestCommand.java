package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.coverage.CoverageRegression;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.CoverageThresholdResolver;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.report.docs.ReportDoc;
import io.tesseraql.report.docs.ReportGenerator;
import io.tesseraql.report.docs.ReportHistory;
import io.tesseraql.report.docs.ReportRegression;
import io.tesseraql.test.TestReport;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql test --app <dir>}: runs the app's declarative test suites against a database and
 * fails on any failure — the CLI-native form of the {@code tesseraql:test} goal (design ch. 18),
 * over the same {@link AppTestRunner} the {@code mcp} dev-tools use. {@code --report} folds in the
 * {@code report} goal: it additionally writes the documentation-portal overlay
 * ({@code .tesseraql/docs/report.json} + {@code history.json}). The overlay is JSON today; a future
 * {@code <fmt>} selector is reserved. With {@code --fail-on-regression} the command also exits
 * non-zero (2) when this run's SQL coverage dropped against the previous run beyond the tolerance —
 * the coverage-regression gate.
 */
@Command(name = "test", description = "Run the app's test suites; --report writes the docs overlay.")
final class TestCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    CliDatasource datasource;

    @Option(names = {"--realm"}, description = "Managed realm id (default: local).")
    String realm = "local";

    @Option(names = {
            "--report-dir"}, description = "Report output dir (default: <app>/work/reports).")
    Path reportDir;

    @Option(names = {
            "--report"}, description = "Also write the documentation-portal report overlay.")
    boolean writeReport;

    @Option(names = {"--run-id"}, description = "Stable run id for the trend (default: timestamp).")
    String runId;

    @Option(names = {
            "--history-limit"}, description = "Runs retained in history.json; 0 keeps all (default: 20).")
    int historyLimit = 20;

    @Option(names = {"--sql-line-threshold"}, description = "Min SQL line coverage % recorded.")
    double sqlLineThreshold;

    @Option(names = {"--sql-branch-threshold"}, description = "Min SQL branch coverage % recorded.")
    double sqlBranchThreshold;

    @Option(names = {
            "--fail-on-regression"}, description = "Exit non-zero if SQL coverage drops vs the previous run (needs --report).")
    boolean failOnRegression;

    @Option(names = {
            "--regression-tolerance"}, description = "Allowed coverage drop (percentage points) before it is a regression.")
    double regressionTolerance;

    @Option(names = {
            "--format"}, defaultValue = "text", description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    Format format;

    @Option(names = {
            "--case"}, description = "Run only the named case(s), exact match; repeatable (default: all).")
    List<String> cases = List.of();

    enum Format {
        text, json
    }

    @Option(names = {"--modules"}, description = "Directory of extra module jars (composes with"
            + " the app's declared tesseraql.modules).")
    java.io.File modules;

    @Override
    public Integer call() throws Exception {
        // Validation rules evaluate expressions, so custom functions install first (the same
        // modules wiring serve boots with).
        CliModules.installAppExtensions(app, modules);
        AppManifest manifest = new ManifestLoader().load(app);
        DriverManagerDataSource dataSource = datasource.resolve(manifest.config());
        Path reports = reportDir != null ? reportDir : app.resolve("work").resolve("reports");
        Files.createDirectories(reports);

        AppTestRunner.RunResult result = new AppTestRunner().run(app, dataSource,
                RealmConfig.managed(realm, "main"), reports, Set.copyOf(cases));
        TestReport report = result.report();
        switch (format) {
            case text -> printText(report);
            case json -> printJson(result);
        }
        boolean regressed = writeReport && writeOverlay(manifest, result);
        if (!report.allPassed()) {
            return 1;
        }
        // A clean test run that regressed coverage (opt-in gate) exits 2, distinct from a test failure.
        return regressed ? 2 : 0;
    }

    private static void printText(TestReport report) {
        System.out.println(
                "TesseraQL tests: " + report.passed() + " passed, " + report.failed() + " failed");
        for (TestReport.TestResult testResult : report.results()) {
            if (!testResult.passed()) {
                System.err.println("FAIL " + testResult.name() + ": " + testResult.message());
            }
        }
    }

    /**
     * The editor test-run contract (docs/vscode-extension.md, Phase 55): the complete per-case
     * results plus per-file SQL coverage with the 1-based covered/coverable line lists, one JSON
     * object on stdout. Files and lines are emitted sorted, so the document is deterministic.
     */
    private static void printJson(AppTestRunner.RunResult result) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode();
        document.put("passed", result.report().passed());
        document.put("failed", result.report().failed());
        document.set("results", mapper.valueToTree(result.report().results()));
        ArrayNode sql = document.putArray("sql");
        result.coverage().reports().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    SqlCoverageReport report = entry.getValue();
                    ObjectNode file = sql.addObject();
                    file.put("file", entry.getKey());
                    file.put("lineRatio", report.lineRatio());
                    file.put("branchRatio", report.branchRatio());
                    file.set("coveredLines",
                            mapper.valueToTree(report.coveredLines().stream().sorted().toList()));
                    file.set("coverableLines",
                            mapper.valueToTree(report.coverableLines().stream().sorted().toList()));
                });
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }

    /**
     * Writes the run-dependent portal overlay, exactly as the {@code report} goal does, and evaluates
     * the opt-in coverage-regression gate. Returns {@code true} when {@code --fail-on-regression} is
     * set and this run's SQL coverage dropped against the previous run beyond the tolerance.
     */
    private boolean writeOverlay(AppManifest manifest, AppTestRunner.RunResult result)
            throws Exception {
        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(manifest.config(),
                sqlLineThreshold, sqlBranchThreshold);
        String generatedAt = Instant.now().toString();
        String resolvedRunId = (runId == null || runId.isBlank()) ? generatedAt : runId;
        ReportGenerator generator = new ReportGenerator();
        ReportDoc doc = generator.generate(manifest, result, thresholds, resolvedRunId,
                generatedAt);
        Path docsDir = app.resolve(".tesseraql").resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("report.json"), generator.toJson(doc));
        // The previous run, captured before this run is appended, is the regression-gate baseline.
        Path historyFile = docsDir.resolve("history.json");
        List<ReportHistory.Entry> priorHistory = ReportHistory.read(historyFile);
        // A non-positive limit keeps the full history (longer-term trends, backlog F9).
        ReportHistory.append(historyFile, ReportHistory.Entry.from(doc), historyLimit);
        System.out.println("Wrote " + docsDir.resolve("report.json"));

        CoverageRegression.Result regression = ReportRegression.check(priorHistory, doc,
                regressionTolerance);
        if (!regression.passed()) {
            regression.violations()
                    .forEach(violation -> System.err.println("Coverage regression: " + violation));
        }
        return failOnRegression && !regression.passed();
    }
}
