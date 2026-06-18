package io.tesseraql.maven;

import io.tesseraql.coverage.CoverageRegression;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.CoverageThresholdResolver;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.report.docs.ReportDoc;
import io.tesseraql.report.docs.ReportGenerator;
import io.tesseraql.report.docs.ReportHistory;
import io.tesseraql.report.docs.ReportRegression;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Runs the app's declarative test suites and writes the documentation-portal report overlay
 * (documentation portal v2): {@code report.json} (the last run's results and SQL/item coverage,
 * joined per route) and {@code history.json} (a bounded run-trend ring) into the app home's reserved
 * {@code .tesseraql/docs/} namespace, where the runtime portal reads them.
 *
 * <p>The overlay is run-dependent and is deliberately <strong>not</strong> packaged into the
 * reproducible {@code .tqlapp}: {@code AppPackager} excludes the source {@code .tesseraql/}
 * namespace, and this goal binds to {@code integration-test} (after {@code package}). Unlike the
 * {@code coverage} goal, it never fails the build on test failures or low coverage — its job is to
 * record the run, including failing ones, for the portal to surface. The one exception is the opt-in
 * coverage-regression gate ({@code tesseraql.failOnCoverageRegression}): when enabled, the build
 * fails if this run's SQL coverage dropped against the previous run by more than the tolerance.
 */
@Mojo(name = "report", defaultPhase = LifecyclePhase.INTEGRATION_TEST, threadSafe = true)
public class ReportMojo extends AbstractMojo {

    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    @Parameter(property = "tesseraql.jdbcUrl", required = true)
    private String jdbcUrl;

    @Parameter(property = "tesseraql.username")
    private String username;

    @Parameter(property = "tesseraql.password")
    private String password;

    @Parameter(property = "tesseraql.realm", defaultValue = "local")
    private String realm;

    @Parameter(property = "tesseraql.reportDir", defaultValue = "${project.build.directory}/tesseraql-reports")
    private File reportDir;

    /** A stable run identity for the trend (e.g. a CI build number); defaults to the timestamp. */
    @Parameter(property = "tesseraql.runId")
    private String runId;

    /** Number of runs retained in {@code history.json}; 0 (or negative) keeps the full history. */
    @Parameter(property = "tesseraql.historyLimit", defaultValue = "20")
    private int historyLimit;

    /** Default minimum SQL line-coverage percentage recorded in the gate (overridden by config). */
    @Parameter(property = "tesseraql.sqlLineThreshold", defaultValue = "0")
    private double sqlLineThreshold;

    /** Default minimum SQL branch-coverage percentage recorded in the gate (overridden by config). */
    @Parameter(property = "tesseraql.sqlBranchThreshold", defaultValue = "0")
    private double sqlBranchThreshold;

    /** Fail the build when SQL coverage regresses against the previous run (the coverage regression gate). */
    @Parameter(property = "tesseraql.failOnCoverageRegression", defaultValue = "false")
    private boolean failOnCoverageRegression;

    /** Allowed coverage drop (percentage points) before it counts as a regression. */
    @Parameter(property = "tesseraql.coverageRegressionTolerance", defaultValue = "0")
    private double coverageRegressionTolerance;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username,
                password);
        AppTestRunner.RunResult result = new AppTestRunner().run(appHome.toPath(), dataSource,
                RealmConfig.managed(realm, "main"), reportDir.toPath());

        AppManifest manifest = new ManifestLoader().load(appHome.toPath());
        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(manifest.config(),
                sqlLineThreshold, sqlBranchThreshold);

        String generatedAt = Instant.now().toString();
        String resolvedRunId = (runId == null || runId.isBlank()) ? generatedAt : runId;
        ReportGenerator generator = new ReportGenerator();
        ReportDoc report = generator.generate(manifest, result, thresholds, resolvedRunId,
                generatedAt);

        Path docsDir = appHome.toPath().resolve(".tesseraql").resolve("docs");
        // The previous run, captured before this run is appended, is the regression-gate baseline.
        List<ReportHistory.Entry> priorHistory;
        try {
            Files.createDirectories(docsDir);
            Files.writeString(docsDir.resolve("report.json"), generator.toJson(report));
            Path historyFile = docsDir.resolve("history.json");
            priorHistory = ReportHistory.read(historyFile);
            // A non-positive limit keeps the full history (longer-term trends, backlog F9).
            ReportHistory.append(historyFile, ReportHistory.Entry.from(report), historyLimit);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to write report overlay to " + docsDir, ex);
        }

        getLog().info(String.format(
                "TesseraQL report: %d passed, %d failed; SQL line %.0f%%, branch %.0f%%; gate %s",
                report.summary().passed(), report.summary().failed(),
                report.summary().sqlLineRatio() * 100, report.summary().sqlBranchRatio() * 100,
                report.gate().passed() ? "passed" : "FAILED"));
        getLog().info("Wrote " + docsDir.resolve("report.json"));

        checkRegression(report, priorHistory);
    }

    /**
     * The opt-in coverage-regression gate (Studio backlog category 3): compares this run's aggregate
     * SQL coverage to the previous run's and, when {@code failOnCoverageRegression} is set, fails the
     * build if it dropped beyond the tolerance. Always logs a regression as a warning so it is
     * visible even when the gate is advisory.
     */
    private void checkRegression(ReportDoc report, List<ReportHistory.Entry> priorHistory)
            throws MojoFailureException {
        CoverageRegression.Result regression = ReportRegression.check(priorHistory, report,
                coverageRegressionTolerance);
        if (regression.passed()) {
            return;
        }
        regression.violations().forEach(violation -> getLog().warn("Coverage regression: "
                + violation));
        if (failOnCoverageRegression) {
            throw new MojoFailureException("Coverage regressed against the previous run: "
                    + String.join("; ", regression.violations()));
        }
    }
}
