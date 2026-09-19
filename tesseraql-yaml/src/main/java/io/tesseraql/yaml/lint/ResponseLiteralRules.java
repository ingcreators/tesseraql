package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.app.ResponseLiterals;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * The response literals the edge would not honour as written, as findings
 * (docs/audit-low-leads.md EH-06): a file response's {@code charset=} the body is not written
 * in, a redirect location with whitespace at either end — from the predicate the compiler
 * refuses from, called from the document families in their loops like the recipe shape.
 */
final class ResponseLiteralRules {

    private ResponseLiteralRules() {
    }

    static void report(LintContext context, AppConfig config, Path document,
            RouteDefinition definition, String urlPath, String source,
            List<LintFinding> findings) {
        for (ExportDeclarations.Violation violation : ResponseLiterals.violations(
                ExportRules.appName(config), definition, urlPath)) {
            String token = violation.key().substring(violation.key().lastIndexOf('.') + 1) + ":";
            findings.add(new LintFinding(violation.code().toString(), ERROR, source,
                    violation.message(), context.lineWithin(document, "response:", token),
                    null));
        }
    }
}
