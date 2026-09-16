package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.app.RecipeShape;
import io.tesseraql.yaml.app.RouteFiles;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * The files a document names, as findings (docs/audit-low-leads.md slice 14): each 2-way SQL
 * statement and the response's page template, resolved by the one resolver the compiler
 * refuses from — outside the application home, or not there — each finding carrying the
 * resolver's own code. Called from the document families a route, a tool and a consumer
 * belong to, in their loops, like the recipe shape. The export template is the export
 * block's own arm ({@link ExportDeclarations}), judged with the rest of it.
 */
final class RouteFileRules {

    private RouteFileRules() {
    }

    static void report(LintContext context, AppConfig config, Path document,
            RouteDefinition definition, RecipeShape.Surface surface, String source,
            List<LintFinding> findings) {
        String subject = surface.noun() + " '" + ExportDeclarations.bounded(definition.id())
                + "'";
        Path directory = document.getParent();
        for (RouteFiles.Reference reference : RouteFiles.references(definition)) {
            String head = RouteFiles.head(ExportRules.appName(config), subject, reference.key());
            try {
                switch (reference.kind()) {
                    case SQL -> RouteFiles.sql(context.appHome(), directory, reference.declared(),
                            null, head);
                    case PAGE -> RouteFiles.page(context.appHome(), directory,
                            reference.declared(), head);
                    case EXPORT_TEMPLATE -> {
                        // The export block's arm, reported with the block's other refusals.
                    }
                }
            } catch (TqlException refused) {
                findings.add(new LintFinding(refused.code().toString(), ERROR, source,
                        refused.getMessage(),
                        context.lineOf(document, reference.declared()), null));
            }
        }
    }
}
