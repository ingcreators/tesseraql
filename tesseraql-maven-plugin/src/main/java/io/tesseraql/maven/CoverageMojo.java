package io.tesseraql.maven;

import io.tesseraql.coverage.CoverageGate;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Runs the app's test suites, collects SQL coverage, writes the coverage report, and fails the
 * build when line or branch coverage is below the threshold (design ch. 14, 18 {@code coverage}).
 * Thresholds come from the app's {@code coverage.thresholds.*} config, falling back to the goal
 * parameters.
 */
@Mojo(name = "coverage", defaultPhase = LifecyclePhase.INTEGRATION_TEST, threadSafe = true)
public class CoverageMojo extends AbstractMojo {

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

    @Parameter(property = "tesseraql.reportDir",
            defaultValue = "${project.build.directory}/tesseraql-reports")
    private File reportDir;

    /** Default minimum SQL branch-coverage percentage (overridden by config). */
    @Parameter(property = "tesseraql.sqlBranchThreshold", defaultValue = "0")
    private double sqlBranchThreshold;

    /** Default minimum SQL line-coverage percentage (overridden by config). */
    @Parameter(property = "tesseraql.sqlLineThreshold", defaultValue = "0")
    private double sqlLineThreshold;

    @Override
    public void execute() throws MojoFailureException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        AppTestRunner.RunResult result = new AppTestRunner().run(
                appHome.toPath(), dataSource, RealmConfig.managed(realm, "main"), reportDir.toPath());

        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(
                loadConfig(appHome.toPath()), sqlLineThreshold, sqlBranchThreshold);
        CoverageGate.Result gate = CoverageGate.check(result.coverage(), thresholds);
        gate.violations().forEach(getLog()::error);
        getLog().info(String.format("TesseraQL coverage gate: %s (line >= %.0f%%, branch >= %.0f%%)",
                gate.passed() ? "passed" : "FAILED",
                thresholds.sqlLine() * 100, thresholds.sqlBranch() * 100));
        if (!gate.passed()) {
            throw new MojoFailureException("TesseraQL coverage below threshold: "
                    + gate.violations().size() + " file(s)");
        }
    }

    /** Loads the app config for threshold overrides, or {@code null} when it cannot be read. */
    private static AppConfig loadConfig(Path appHome) {
        try {
            return new ManifestLoader().load(appHome).config();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
