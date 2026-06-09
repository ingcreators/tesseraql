package io.tesseraql.maven;

import io.tesseraql.identity.RealmConfig;
import io.tesseraql.test.TestReport;
import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Runs the app's declarative test suites against a database and writes reports (design ch. 18
 * {@code test}). Fails the build when any case fails.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.INTEGRATION_TEST, threadSafe = true)
public class TestMojo extends AbstractMojo {

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

    @Parameter(property = "tesseraql.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Override
    public void execute() throws MojoFailureException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        TestReport report = new AppTestRunner().run(
                appHome.toPath(), dataSource, RealmConfig.managed(realm, "main"), reportDir.toPath());

        getLog().info("TesseraQL tests: " + report.passed() + " passed, " + report.failed() + " failed");
        for (TestReport.TestResult result : report.results()) {
            if (!result.passed()) {
                getLog().error("FAIL " + result.name() + ": " + result.message());
            }
        }
        if (failOnError && !report.allPassed()) {
            throw new MojoFailureException("TesseraQL tests failed: " + report.failed() + " case(s)");
        }
    }
}
