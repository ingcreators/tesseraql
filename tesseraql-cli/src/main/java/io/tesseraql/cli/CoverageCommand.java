package io.tesseraql.cli;

import io.tesseraql.coverage.CoverageGate;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.CoverageThresholdResolver;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql coverage --app <dir>}: runs the app's suites, collects SQL coverage and fails
 * when line or branch coverage is below the threshold (design ch. 14, 18) — the CLI-native form of
 * the {@code tesseraql:coverage} goal. Thresholds come from the app's {@code coverage.thresholds.*}
 * config, falling back to the options.
 */
@Command(name = "coverage", description = "Run suites and enforce the SQL coverage gate.")
final class CoverageCommand implements Callable<Integer> {

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
            "--sql-line-threshold"}, description = "Min SQL line coverage % (else config).")
    double sqlLineThreshold;

    @Option(names = {
            "--sql-branch-threshold"}, description = "Min SQL branch coverage % (else config).")
    double sqlBranchThreshold;

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
                RealmConfig.managed(realm, "main"), reports);
        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(manifest.config(),
                sqlLineThreshold, sqlBranchThreshold);
        CoverageGate.Result gate = CoverageGate.check(result.coverage(), result.kinds(),
                thresholds);
        gate.violations().forEach(System.err::println);
        System.out.println(String.format(
                "TesseraQL coverage gate: %s (line >= %.0f%%, branch >= %.0f%%)",
                gate.passed() ? "passed" : "FAILED",
                thresholds.sqlLine() * 100, thresholds.sqlBranch() * 100));
        return gate.passed() ? 0 : 1;
    }
}
