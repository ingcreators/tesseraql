package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Site;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The pieces a recipe reads, judged where the document is written (docs/audit-low-leads.md
 * slice 8): the response arm the recipe renders, the arm each source and step executes, the
 * import block and row step of a {@code file-import}, the {@code main} source an export writes.
 * One predicate on both altitudes — the linter reports from it and the compiler refuses from
 * it — because every piece here used to be a {@code NullPointerException} out of the compiler
 * that lint had passed clean: the JSON renderer dereferencing {@code response.json} on a route
 * that declared no {@code response:} (the shape {@code docs/multi-datasource.md} once showed as
 * complete), {@code Path.resolve(null)} on a step whose {@code sql:} named no {@code file:},
 * the import processor reading a block that was not there. Each took the whole application
 * down at boot with a JDK message naming neither the route nor the key.
 *
 * <p>The vocabulary is the compiler's own: a JSON recipe ends in {@code json:} or
 * {@code redirect:}, a page recipe in {@code html:} or {@code file:}, and a source or a step
 * runs through exactly one of the arms {@link Binding} declares. What the recipe does not read
 * is not judged here — a file recipe's optional {@code response.html:} is the import
 * compiler's own rule, and a tool answers through the MCP renderer whether or not it declares
 * a response.
 */
public final class RecipeShape {

    /**
     * TQL-YAML-1066: a route declares no response arm its recipe renders — a JSON recipe
     * ({@code query-json}, {@code command-json}, {@code webhook}) answers through
     * {@code response.json:} or {@code response.redirect:}, a page recipe ({@code query-html},
     * {@code page}) through {@code response.html:} or {@code response.file:}, and the document
     * declares neither. Reported at lint and refused at boot from the same predicate.
     */
    public static final TqlErrorCode NO_RESPONSE_ARM = new TqlErrorCode(TqlDomain.YAML, 1066);

    /**
     * TQL-YAML-1067: a source or a step declares no arm to run — nothing under {@code sql:}
     * names a {@code file:}, and there is no {@code contract:}, {@code service:} or {@code http:}
     * (nor, on a command step, a {@code sequence:}); or an {@code http:} block names no
     * {@code url:}. Reported at lint and refused at boot from the same predicate.
     */
    public static final TqlErrorCode NO_BINDING_ARM = new TqlErrorCode(TqlDomain.YAML, 1067);

    /** The route recipes that end in a JSON renderer. */
    private static final Set<String> JSON_RECIPES = Set.of("query-json", "command-json",
            "webhook");

    /** The route recipes that end in a template renderer. */
    private static final Set<String> PAGE_RECIPES = Set.of("query-html", "page");

    /** The route recipes whose rows are the document's {@code main} source. */
    private static final Set<String> EXPORT_RECIPES = Set.of("query-export", "file-export");

    /** Which document the definition is — the words its refusals open with. */
    public enum Surface {
        ROUTE("route"), TOOL("MCP tool"), CONSUMER("consumer");

        private final String noun;

        Surface(String noun) {
            this.noun = noun;
        }

        /** The words a refusal opens with: {@code route}, {@code MCP tool}, {@code consumer}. */
        public String noun() {
            return noun;
        }
    }

    private RecipeShape() {
    }

    /**
     * Every piece {@code definition}'s recipe reads and the document does not declare. A route
     * whose recipe is unknown is judged by nothing here — the recipe rule owns that refusal,
     * and a response arm for a recipe that does not exist would only send the author the
     * wrong way.
     */
    public static List<Violation> violations(String app, RouteDefinition definition,
            Surface surface, String urlPath) {
        List<Violation> out = new ArrayList<>();
        String subject = surface.noun + " '" + ExportDeclarations.bounded(definition.id()) + "'";
        String recipe = definition.recipe();
        if (surface == Surface.ROUTE) {
            responseArm(app, subject, definition, out);
            if ("file-import".equals(recipe)) {
                importPieces(Site.route(app, definition, urlPath), definition, out);
            }
            if (EXPORT_RECIPES.contains(recipe)) {
                exportRows(Site.route(app, definition, urlPath), definition, out);
            }
        }
        definition.sources().forEach((name, binding) -> bindingArm(app, subject,
                "sources." + name, binding, false, out));
        // A file-import's one step is its row write, judged above as the import's own piece.
        if (!"file-import".equals(recipe)) {
            definition.steps().forEach((name, binding) -> bindingArm(app, subject,
                    "steps." + name, binding, true, out));
        }
        return out;
    }

    /**
     * The response arm the recipe's terminal renderer dereferences. {@code response:} present
     * with the wrong arm is the same refusal: {@code response: {session: {rotate: true}}} on a
     * command is a route the JSON renderer cannot build, and {@code html:} on a
     * {@code query-json} is a page arm the JSON recipe never reads.
     */
    private static void responseArm(String app, String subject, RouteDefinition definition,
            List<Violation> out) {
        ResponseSpec response = definition.response();
        String recipe = definition.recipe();
        if (JSON_RECIPES.contains(recipe)) {
            if (response == null || (response.json() == null && response.redirect() == null)) {
                out.add(new Violation(NO_RESPONSE_ARM, Kind.INVALID, "response",
                        prefix(app, subject, "response") + "a " + recipe + " route answers"
                                + " through response.json: or response.redirect:, and the"
                                + " document declares neither" + declaredArms(response)));
            }
        } else if (PAGE_RECIPES.contains(recipe)) {
            if (response == null || (response.html() == null && response.file() == null)) {
                out.add(new Violation(NO_RESPONSE_ARM, Kind.INVALID, "response",
                        prefix(app, subject, "response") + "a " + recipe + " route renders"
                                + " response.html: (a template: or a view:) or response.file:,"
                                + " and the document declares neither" + declaredArms(response)));
            }
        }
    }

    /** The arms the document did write, so the sentence says what was mistaken for one. */
    private static String declaredArms(ResponseSpec response) {
        if (response == null) {
            return " (there is no response: block)";
        }
        List<String> declared = new ArrayList<>();
        if (response.json() != null) {
            declared.add("json:");
        }
        if (response.html() != null) {
            declared.add("html:");
        }
        if (response.redirect() != null) {
            declared.add("redirect:");
        }
        if (response.file() != null) {
            declared.add("file:");
        }
        if (response.text() != null) {
            declared.add("text:");
        }
        if (response.stream() != null) {
            declared.add("stream:");
        }
        if (response.session() != null) {
            declared.add("session:");
        }
        return declared.isEmpty()
                ? " (the response: block is empty)"
                : " (the response: block declares " + String.join(", ", declared) + ")";
    }

    /**
     * The arm a source or step runs through — the branches the compiler's execution step
     * dispatches on, in its order: a service, a contract, else the {@code sql:} file; an
     * {@code http:} block is dispatched before them, and a {@code sequence:} rides the command
     * transaction, so it is a step's arm and never a source's.
     */
    private static void bindingArm(String app, String subject, String key, Binding binding,
            boolean step, List<Violation> out) {
        if (binding.isSql() || binding.isContract() || binding.isService() || binding.isHttp()
                || (step && binding.isSequence())) {
            return;
        }
        String head = prefix(app, subject, key);
        if (binding.declaresHttp()) {
            out.add(new Violation(NO_BINDING_ARM, Kind.INVALID, key, head + "the http: arm"
                    + " names no url: to call"));
            return;
        }
        out.add(new Violation(NO_BINDING_ARM, Kind.INVALID, key, head + "the "
                + (step ? "step" : "source") + " declares no arm to run - sql: { file: ... },"
                + " contract:, service:" + (step ? ", http: or sequence:" : " or http:")));
    }

    /** The import block and the row write a {@code file-import} route reads. */
    private static void importPieces(Site site, RouteDefinition definition,
            List<Violation> out) {
        if (definition.fileImport() == null) {
            out.add(new Violation(ExportDeclarations.INCOMPLETE, Kind.INVALID, "import",
                    ExportDeclarations.missingImportBlock(site)));
        }
        Binding row = definition.rowStep();
        if (row == null || row.file() == null || row.file().isBlank()) {
            out.add(new Violation(ExportDeclarations.INCOMPLETE, Kind.INVALID, "steps",
                    ExportDeclarations.missingRowStep(site)));
        }
    }

    /**
     * The rows an export writes are the document's {@code main} source, on every export
     * recipe (docs/unified-sources.md decision 7), read as a 2-way SQL file.
     */
    private static void exportRows(Site site, RouteDefinition definition, List<Violation> out) {
        Binding main = definition.main();
        if (main == null || main.file() == null || main.file().isBlank()) {
            out.add(new Violation(ExportDeclarations.INCOMPLETE, Kind.INVALID, "sources.main",
                    ExportDeclarations.missingMain(site)));
        }
    }

    /** {@link Site#prefix}'s shape, for the surfaces that carry no site. */
    private static String prefix(String app, String subject, String key) {
        return "app '" + ExportDeclarations.bounded(app) + "': " + subject + " " + key + ": ";
    }
}
