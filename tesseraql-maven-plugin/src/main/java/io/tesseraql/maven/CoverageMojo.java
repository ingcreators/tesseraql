package io.tesseraql.maven;

import io.tesseraql.coverage.CoverageGate;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Runs the app's test suites, collects SQL coverage, writes the coverage report, and fails the
 * build when branch coverage is below the threshold (design ch. 14, 18 {@code coverage}).
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

    /** Minimum SQL branch-coverage percentage. */
    @Parameter(property = "tesseraql.sqlBranchThreshold", defaultValue = "0")
    private double sqlBranchThreshold;

    @Override
    public void execute() throws MojoFailureException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        AppTestRunner.RunResult result = new AppTestRunner().run(
                appHome.toPath(), dataSource, RealmConfig.managed(realm, "main"), reportDir.toPath());

        CoverageGate.Result gate = CoverageGate.check(
                result.coverage(), CoverageThresholds.ofPercent(sqlBranchThreshold));
        gate.violations().forEach(getLog()::error);
        getLog().info("TesseraQL coverage gate: " + (gate.passed() ? "passed" : "FAILED")
                + " (threshold " + sqlBranchThreshold + "% branch)");
        if (!gate.passed()) {
            throw new MojoFailureException("TesseraQL coverage below threshold: "
                    + gate.violations().size() + " file(s)");
        }
    }
}
