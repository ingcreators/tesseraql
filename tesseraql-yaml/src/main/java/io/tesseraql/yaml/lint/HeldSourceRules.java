package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.app.HeldSources;
import io.tesseraql.yaml.app.RecipeShape;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * A source's {@code cache:} where nothing can be held, as findings (docs/caching.md decision
 * 6) — judged by the predicate the compiler refuses from, so a document that lints clean is
 * one the boot accepts. Called from the document families a route, a tool and a consumer
 * belong to, beside their {@code invalidates:} check.
 */
final class HeldSourceRules {

    private HeldSourceRules() {
    }

    static void report(LintContext context, AppConfig config, Path document,
            RouteDefinition definition, RecipeShape.Surface surface, String source,
            List<LintFinding> findings) {
        for (ExportDeclarations.Violation violation : HeldSources.violations(
                ExportRules.appName(config), definition, surface)) {
            findings.add(new LintFinding(violation.code().toString(), ERROR, source,
                    violation.message(), line(context, document, violation.key()), null));
        }
    }

    /** The line of the source or step whose {@code cache:} is wrong. */
    private static Integer line(LintContext context, Path document, String key) {
        String name = key.substring(key.indexOf('.') + 1, key.lastIndexOf('.'));
        if (key.startsWith("sources.")) {
            return context.lineWithin(document, "sources:", name + ":");
        }
        return context.lineWithin(document, "steps:", "id: " + name);
    }
}
