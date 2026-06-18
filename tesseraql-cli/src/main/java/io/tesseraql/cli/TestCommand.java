package io.tesseraql.cli;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.CoverageThresholdResolver;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.report.docs.ReportDoc;
import io.tesseraql.report.docs.ReportGenerator;
import io.tesseraql.report.docs.ReportHistory;
import io.tesseraql.test.TestReport;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * {@code <fmt>} selector is reserved.
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

    @Override
    public Integer call() throws Exception {
        AppManifest manifest = new ManifestLoader().load(app);
        DriverManagerDataSource dataSource = datasource.resolve(manifest.config());
        Path reports = reportDir != null ? reportDir : app.resolve("work").resolve("reports");
        Files.createDirectories(reports);

        AppTestRunner.RunResult result = new AppTestRunner().run(app, dataSource,
                RealmConfig.managed(realm, "main"), reports);
        TestReport report = result.report();
        System.out.println(
                "TesseraQL tests: " + report.passed() + " passed, " + report.failed() + " failed");
        for (TestReport.TestResult testResult : report.results()) {
            if (!testResult.passed()) {
                System.err.println("FAIL " + testResult.name() + ": " + testResult.message());
            }
        }
        if (writeReport) {
            writeOverlay(manifest, result);
        }
        return report.allPassed() ? 0 : 1;
    }

    /** Writes the run-dependent portal overlay, exactly as the {@code report} goal does. */
    private void writeOverlay(AppManifest manifest, AppTestRunner.RunResult result)
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
        // A non-positive limit keeps the full history (longer-term trends, backlog F9).
        ReportHistory.append(docsDir.resolve("history.json"), ReportHistory.Entry.from(doc),
                historyLimit);
        System.out.println("Wrote " + docsDir.resolve("report.json"));
    }
}
