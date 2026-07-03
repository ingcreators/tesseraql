package io.tesseraql.cli;

import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql lint --app <dir>}: runs the app linter (recipes, SQL files, security policies)
 * and fails on errors — the CLI-native form of the {@code tesseraql:lint} goal (design ch. 18),
 * over the same {@link AppLinter} engine the {@code mcp} dev-tools use.
 */
@Command(name = "lint", description = "Lint the app home, failing on errors.")
final class LintCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Option(names = {"--fail-on-warning"}, description = "Treat warnings as failures too.")
    boolean failOnWarning;

    @Override
    public Integer call() {
        List<LintFinding> findings = new AppLinter().lint(app);
        long errors = 0;
        for (LintFinding finding : findings) {
            String line = finding.code() + " [" + finding.severity() + "] "
                    + finding.location() + ": " + finding.message();
            if (finding.isError()) {
                errors++;
                System.err.println(line);
            } else {
                System.out.println(line);
            }
        }
        System.out.println(
                "TesseraQL lint: " + findings.size() + " finding(s), " + errors + " error(s)");
        return errors > 0 || (failOnWarning && !findings.isEmpty()) ? 1 : 0;
    }
}
