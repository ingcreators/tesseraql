package io.tesseraql.maven;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.CoverageThresholdResolver;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.report.docs.ReportDoc;
import io.tesseraql.report.docs.ReportGenerator;
import io.tesseraql.report.docs.ReportHistory;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
 * record the run, including failing ones, for the portal to surface.
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

    /** Number of runs retained in {@code history.json}. */
    @Parameter(property = "tesseraql.historyLimit", defaultValue = "20")
    private int historyLimit;

    /** Default minimum SQL line-coverage percentage recorded in the gate (overridden by config). */
    @Parameter(property = "tesseraql.sqlLineThreshold", defaultValue = "0")
    private double sqlLineThreshold;

    /** Default minimum SQL branch-coverage percentage recorded in the gate (overridden by config). */
    @Parameter(property = "tesseraql.sqlBranchThreshold", defaultValue = "0")
    private double sqlBranchThreshold;

    @Override
    public void execute() throws MojoExecutionException {
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
        try {
            Files.createDirectories(docsDir);
            Files.writeString(docsDir.resolve("report.json"), generator.toJson(report));
            ReportHistory.append(docsDir.resolve("history.json"), ReportHistory.Entry.from(report),
                    Math.max(1, historyLimit));
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to write report overlay to " + docsDir, ex);
        }

        getLog().info(String.format(
                "TesseraQL report: %d passed, %d failed; SQL line %.0f%%, branch %.0f%%; gate %s",
                report.summary().passed(), report.summary().failed(),
                report.summary().sqlLineRatio() * 100, report.summary().sqlBranchRatio() * 100,
                report.gate().passed() ? "passed" : "FAILED"));
        getLog().info("Wrote " + docsDir.resolve("report.json"));
    }
}
