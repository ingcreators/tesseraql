package io.tesseraql.maven;

import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import java.io.File;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Lints a TesseraQL app home, failing the build on errors (design ch. 18 {@code lint}).
 */
@Mojo(name = "lint", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class LintMojo extends AbstractMojo {

    /** The external app home to lint. */
    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    /** Whether warnings should also fail the build. */
    @Parameter(property = "tesseraql.failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    @Override
    public void execute() throws MojoFailureException {
        List<LintFinding> findings = new AppLinter().lint(appHome.toPath());
        long errors = 0;
        for (LintFinding finding : findings) {
            String line = finding.code() + " [" + finding.severity() + "] "
                    + finding.location() + ": " + finding.message();
            if (finding.isError()) {
                errors++;
                getLog().error(line);
            } else {
                getLog().warn(line);
            }
        }
        getLog().info(
                "TesseraQL lint: " + findings.size() + " finding(s), " + errors + " error(s)");
        boolean failed = errors > 0 || (failOnWarning && !findings.isEmpty());
        if (failed) {
            throw new MojoFailureException(
                    "TesseraQL lint failed with " + findings.size() + " finding(s)");
        }
    }
}
