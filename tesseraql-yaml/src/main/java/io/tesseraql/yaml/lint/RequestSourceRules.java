package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.app.RecipeShape;
import io.tesseraql.yaml.app.RequestSources;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.PageSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a document reads the request from, as findings (docs/audit-low-leads.md slice 9):
 * a {@code header.<name>} source outside a {@code service:} binding's {@code params:} from the
 * predicate the compiler refuses from, and — on a route, whose method the file name says —
 * the two body sources that never bind: {@code body.<name>} on a GET, whose body the edge
 * does not read (docs/edge-hygiene.md decision 6), and {@code body.<name>} naming an input the
 * route does not declare while it rejects unknown fields, where a request carrying the field
 * is refused before the bind and one without it binds null. Each used to lint clean and
 * answer a silent null.
 */
final class RequestSourceRules {

    /** A body source on a route that carries no body. */
    static final String BODY_ON_GET = "TQL-YAML-1070";

    /** A body source naming a field the route refuses. */
    static final String UNDECLARED_BODY_FIELD = "TQL-YAML-1071";

    private RequestSourceRules() {
    }

    /** The header predicate, on every surface a route-shaped document has. */
    static void report(LintContext context, AppConfig config, Path document,
            RouteDefinition definition, RecipeShape.Surface surface, String source,
            List<LintFinding> findings) {
        for (ExportDeclarations.Violation violation : RequestSources.violations(
                ExportRules.appName(config), definition, surface)) {
            findings.add(new LintFinding(violation.code().toString(), ERROR, source,
                    violation.message(), line(context, document, violation.key()), null));
        }
    }

    /**
     * The body sources of a route: judged by the method its file name declares and the
     * inputs it declares. A snapshot-paginated GET also answers the pager's POST, so its body
     * is not judged; a route that ignores unknown fields keeps an undeclared body field in the
     * raw body, so only a route that rejects them is told the field never arrives.
     */
    static void lintBodySources(LintContext context, RouteFile route, String source,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        boolean bodiless = "GET".equalsIgnoreCase(route.httpMethod())
                && !(definition.pagination() != null && PageSpec.SNAPSHOT.equals(
                        definition.pagination().effectiveStrategy()));
        boolean declaredBody = definition.effectiveInputPolicy().rejectsUnknownFields();
        for (RequestSources.Source expression : RequestSources.sources(definition)) {
            if (!"body".equals(expression.root()) || expression.name().isEmpty()) {
                continue;
            }
            Integer line = line(context, route.source(), expression.slot());
            String head = expression.where() + ": '" + ExportDeclarations.bounded(
                    expression.expression()) + "' ";
            if (bodiless) {
                findings.add(new LintFinding(BODY_ON_GET, ERROR, source, head
                        + "reads the request body, and a GET carries none - the edge reads no"
                        + " body on a GET, so the value is null on every request; declare"
                        + " input: " + ExportDeclarations.bounded(expression.name())
                        + " and read query." + ExportDeclarations.bounded(expression.name()),
                        line, null));
            } else if (declaredBody && !definition.input().containsKey(expression.name())) {
                findings.add(new LintFinding(UNDECLARED_BODY_FIELD, ERROR, source, head
                        + "names a field the route does not declare under input: - a request"
                        + " carrying it is refused by the mass-assignment guard as an unknown"
                        + " input field, and one without it binds null; declare input: "
                        + ExportDeclarations.bounded(expression.name()), line, null));
            }
        }
    }

    /**
     * The line of the slot that carries the expression: a source is {@code <name>:} inside
     * {@code sources:}, a step {@code id: <name>} inside {@code steps:}, a validation rule
     * {@code <name>:} inside {@code validate:}, an export key its own line under
     * {@code export:}.
     */
    private static Integer line(LintContext context, Path document, String slot) {
        int enrich = slot.indexOf(".enrich.");
        String owner = enrich < 0 ? slot : slot.substring(0, enrich);
        if (owner.startsWith("sources.")) {
            return context.lineWithin(document, "sources:",
                    owner.substring("sources.".length()) + ":");
        }
        if (owner.startsWith("steps.")) {
            return context.lineWithin(document, "steps:",
                    "id: " + owner.substring("steps.".length()));
        }
        if (owner.startsWith("validate.")) {
            return context.lineWithin(document, "validate:",
                    owner.substring("validate.".length()) + ":");
        }
        if (owner.startsWith("export.")) {
            return context.lineWithin(document, "export:",
                    owner.substring("export.".length()) + ":");
        }
        return null;
    }
}
