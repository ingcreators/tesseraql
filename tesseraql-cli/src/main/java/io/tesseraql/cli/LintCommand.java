package io.tesseraql.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 *
 * <p>{@code --format json} prints the cross-surface findings document the MCP dev-tools' lint
 * tool has always emitted — {@code {errors, warnings, findings: [{code, severity, source,
 * message, line, column}]}} — as the one JSON object on stdout, so editors (roadmap Phase 54)
 * parse the same shape agents do. Exit semantics are identical in both formats.
 */
@Command(name = "lint", description = "Lint the app home, failing on errors.")
final class LintCommand implements Callable<Integer> {

    enum Format {
        text, json
    }

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Option(names = {"--fail-on-warning"}, description = "Treat warnings as failures too.")
    boolean failOnWarning;

    @Option(names = {
            "--format"}, defaultValue = "text", description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    Format format;

    @Override
    public Integer call() throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app);
        long errors = findings.stream().filter(LintFinding::isError).count();
        switch (format) {
            case text -> printText(findings, errors);
            case json -> printJson(findings, errors);
        }
        return errors > 0 || (failOnWarning && !findings.isEmpty()) ? 1 : 0;
    }

    private static void printText(List<LintFinding> findings, long errors) {
        for (LintFinding finding : findings) {
            String line = finding.code() + " [" + finding.severity() + "] "
                    + finding.location() + ": " + finding.message();
            (finding.isError() ? System.err : System.out).println(line);
        }
        System.out.println(
                "TesseraQL lint: " + findings.size() + " finding(s), " + errors + " error(s)");
    }

    private static void printJson(List<LintFinding> findings, long errors) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode();
        document.put("errors", errors);
        document.put("warnings", findings.size() - errors);
        document.set("findings", mapper.valueToTree(findings));
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }
}
