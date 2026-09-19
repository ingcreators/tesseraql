package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.app.RecipeShape;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * The pieces a recipe reads, as findings (docs/audit-low-leads.md slice 8): the response arm,
 * each source's and step's arm, a file-import's block and row write, an export's {@code main}
 * — judged by the predicate the compiler refuses from, so a document that lints clean cannot
 * take the boot down with a {@code NullPointerException} naming no route. Called from the
 * document families a route, a tool and a consumer belong to, in their loops, so the findings
 * sit beside that document's others.
 */
final class RecipeShapeRules {

    private RecipeShapeRules() {
    }

    static void report(LintContext context, AppConfig config, Path document,
            RouteDefinition definition, RecipeShape.Surface surface, String urlPath,
            String source, List<LintFinding> findings) {
        for (ExportDeclarations.Violation violation : RecipeShape.violations(
                ExportRules.appName(config), definition, surface, urlPath)) {
            findings.add(new LintFinding(violation.code().toString(), ERROR, source,
                    violation.message(), line(context, document, violation.key()), null));
        }
    }

    /**
     * The line of the piece that is wrong, when the document carries it: a source is
     * {@code <name>:} inside {@code sources:}, a step is {@code id: <name>} inside
     * {@code steps:}, the response block is its own key. A piece that is absent has no line —
     * the finding anchors at the top of the document, which is where the block would go.
     */
    private static Integer line(LintContext context, Path document, String key) {
        if (key.startsWith("sources.")) {
            return context.lineWithin(document, "sources:",
                    key.substring("sources.".length()) + ":");
        }
        if (key.startsWith("steps.")) {
            return context.lineWithin(document, "steps:",
                    "id: " + key.substring("steps.".length()));
        }
        return context.lineOf(document, key + ":");
    }
}
