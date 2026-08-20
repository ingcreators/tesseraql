package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Declarative view documents and the routes that render them.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ViewRules implements LintRule {

    private static final String INVALID_SHELL_MODE = "TQL-VIEW-3317";

    private static final String INVALID_VIEW_BINDING = "TQL-VIEW-3302";

    private static final String VIEW_SOURCE_NOT_A_ROUTE_SOURCE = "TQL-VIEW-3308";

    private static final String VIEW_INPUT_NOT_DECLARED = "TQL-VIEW-3309";

    private static final String SORTABLE_WITHOUT_SORT_INPUTS = "TQL-VIEW-3310";

    private static final String EMBEDDED_VIEW_EMBEDS = "TQL-VIEW-3318";

    private static final String UNKNOWN_SLOT = "TQL-VIEW-3306";

    private static final String INVALID_VIEW_ACTION = "TQL-VIEW-3303";

    private static final String ACTION_FIELD_NOT_DECLARED = "TQL-VIEW-3304";

    private static final String UNKNOWN_WIDGET = "TQL-VIEW-3305";

    private static final String INVALID_VIEW_PATTERN_OVERRIDE = "TQL-VIEW-3307";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintViews(context.appHome(), manifest, findings);
    }

    /**
     * Validates declarative views (roadmap Phase 39, docs/declarative-views.md).
     * Document-shape checks run once per view document — parse errors with the exception's own
     * code, a form's {@code action:} naming a POST route with an {@code input:} block
     * ({@code TQL-VIEW-3303}) whose fields the {@code fields:} entries actually declare
     * ({@code TQL-VIEW-3304}) with known widgets ({@code TQL-VIEW-3305}), slot names and
     * references ({@code TQL-VIEW-3306}/{@code 3302}), and {@code refreshOn:} wiring. Per
     * referencing route: the id resolves in the registry and is not combined with
     * {@code template:} ({@code TQL-VIEW-3302}), and source/search/sort wiring
     * ({@code TQL-VIEW-3308/3309/3310}). An app's {@code templates/tql/view/*.html} pattern
     * override carries the expected fragment signature ({@code TQL-VIEW-3307}, warning).
     */
    void lintViews(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        lintViewDocuments(appHome, manifest, findings);
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            var html = response == null ? null : response.html();
            if (html == null) {
                continue;
            }
            String routeSource = appHome.relativize(route.source()).toString()
                    .replace('\\', '/');
            if (!java.util.Set.of("auto", "always", "never")
                    .contains(html.effectiveShell())) {
                findings.add(new LintFinding(INVALID_SHELL_MODE, ERROR, routeSource,
                        "response.html.shell must be 'auto', 'always' or 'never', got: "
                                + html.shell()));
            }
            // views: binds declarative parts to a template: route (wave 2c): each id must
            // resolve, and a view: route embeds through its own document instead.
            if (!html.views().isEmpty() && html.view() != null) {
                findings.add(new LintFinding(INVALID_VIEW_BINDING, ERROR, routeSource,
                        "response.html.views binds declarative parts to a template: route — a"
                                + " view: route embeds through its own document instead"));
            }
            for (String bound : html.views()) {
                if (manifest.viewById(bound) == null) {
                    findings.add(new LintFinding(INVALID_VIEW_BINDING, ERROR, routeSource,
                            "views: " + bound + " does not resolve to a view document id"));
                }
            }
            if (html.view() == null) {
                continue;
            }
            String source = routeSource;
            if (html.template() != null) {
                findings.add(new LintFinding(INVALID_VIEW_BINDING, ERROR, source,
                        "response.html declares both template: and view: — they are mutually"
                                + " exclusive"));
            }
            io.tesseraql.yaml.manifest.ViewFile viewFile = manifest.viewById(html.view());
            if (viewFile == null) {
                findings.add(new LintFinding(INVALID_VIEW_BINDING, ERROR, source,
                        "view: " + html.view() + " does not resolve to a view document id"
                                + " (ids come from *.view.yml under web/ or templates/)"));
                continue;
            }
            io.tesseraql.yaml.view.ViewSpec spec = viewFile.spec();
            for (io.tesseraql.yaml.view.ViewSpec.Child child : spec.children()) {
                if (!declaresViewSource(route.definition(), child.source())) {
                    findings.add(new LintFinding(VIEW_SOURCE_NOT_A_ROUTE_SOURCE, ERROR, source,
                            "view " + spec.id() + ": children source " + child.source()
                                    + " is not a source of the route"
                                    + " (a sources: entry, or main)"));
                }
            }
            for (io.tesseraql.yaml.view.ViewSpec.Panel panel : spec.panels()) {
                String panelSource = panel.source() == null || panel.source().isBlank()
                        ? RouteDefinition.MAIN
                        : panel.source();
                if (!declaresViewSource(route.definition(), panelSource)) {
                    findings.add(new LintFinding(VIEW_SOURCE_NOT_A_ROUTE_SOURCE, ERROR, source,
                            "view " + spec.id() + ": panel source " + panelSource
                                    + " is not a source of the route"
                                    + " (a sources: entry, or main)"));
                }
            }
            if (io.tesseraql.yaml.view.ViewSpec.LIST.equals(spec.view())) {
                var inputs = route.definition().input();
                if (spec.search() != null
                        && (inputs == null || !inputs.containsKey(spec.search()))) {
                    findings.add(new LintFinding(VIEW_INPUT_NOT_DECLARED, ERROR, source,
                            "view " + spec.id() + ": search: " + spec.search()
                                    + " is not a declared input of the route"));
                }
                boolean sortable = spec.columns().stream()
                        .anyMatch(io.tesseraql.yaml.view.ViewSpec.Column::isSortable);
                if (sortable && (inputs == null || !inputs.containsKey("sort")
                        || !inputs.containsKey("dir"))) {
                    findings.add(new LintFinding(SORTABLE_WITHOUT_SORT_INPUTS, ERROR, source,
                            "view " + spec.id() + ": sortable columns need the route to declare"
                                    + " sort and dir inputs its SQL applies"));
                }
            }
        }
        lintViewOverrides(appHome, findings);
    }

    /**
     * The per-document pass (docs/view-composition.md wave 1): every {@code *.view.yml} under
     * {@code web/} and {@code templates/} parses — reported once per document with the parse
     * error's own code (TQL-VIEW-3301/3313/3314), not once per referencing route. Parseable
     * documents are already in the manifest's registry; duplicate ids fail the manifest load
     * itself (TQL-VIEW-3315, the domains posture).
     */
    private void lintViewDocuments(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        java.util.Set<Path> indexed = new java.util.HashSet<>();
        io.tesseraql.yaml.domain.FieldDomains appDomains = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        for (io.tesseraql.yaml.manifest.ViewFile view : manifest.views()) {
            indexed.add(view.source());
            String source = appHome.relativize(view.source()).toString().replace('\\', '/');
            io.tesseraql.yaml.view.ViewSpec spec = view.spec();
            if (io.tesseraql.yaml.view.ViewSpec.FORM.equals(spec.view())) {
                lintFormView(manifest, source, spec, findings);
            }
            LiveViewRules.lintRefreshOn(manifest, source, spec, findings);
            // Read-side domain references (wave 3a): a column/field domain: must be declared.
            java.util.stream.Stream.concat(
                    spec.columns().stream().map(io.tesseraql.yaml.view.ViewSpec.Column::domain),
                    spec.fields().stream().map(io.tesseraql.yaml.view.ViewSpec.Field::domain))
                    .filter(java.util.Objects::nonNull)
                    .forEach(name -> {
                        try {
                            appDomains.require(name, "view " + spec.id());
                        } catch (io.tesseraql.core.error.TqlException ex) {
                            findings.add(new LintFinding(ex.code().toString(), ERROR, source,
                                    ex.getMessage()));
                        }
                    });
            // Embedded views (docs/view-composition.md wave 2b): the id resolves in the
            // registry, and the embedded document does not embed further (depth is 1).
            java.util.List<String> embeds = new ArrayList<>();
            spec.children().stream().map(io.tesseraql.yaml.view.ViewSpec.Child::view)
                    .filter(java.util.Objects::nonNull).forEach(embeds::add);
            spec.panels().stream().map(io.tesseraql.yaml.view.ViewSpec.Panel::view)
                    .filter(java.util.Objects::nonNull).forEach(embeds::add);
            for (String embedId : embeds) {
                io.tesseraql.yaml.manifest.ViewFile embedded = manifest.viewById(embedId);
                if (embedded == null) {
                    findings.add(new LintFinding(INVALID_VIEW_BINDING, ERROR, source,
                            "view " + spec.id() + ": embedded view " + embedId
                                    + " does not resolve to a view document id"));
                    continue;
                }
                boolean embedsFurther = embedded.spec().children().stream()
                        .anyMatch(child -> child.view() != null)
                        || embedded.spec().panels().stream()
                                .anyMatch(panel -> panel.view() != null);
                if (embedsFurther) {
                    findings.add(new LintFinding(EMBEDDED_VIEW_EMBEDS, ERROR, source,
                            "view " + spec.id() + ": embedded view " + embedId
                                    + " embeds views itself — embedding depth is 1"));
                }
            }
            Path viewDir = view.source().getParent();
            for (String slotName : spec.slots().keySet()) {
                java.util.Set<String> allowed = io.tesseraql.yaml.view.ViewSpec
                        .slotsFor(spec.view());
                if (!allowed.contains(slotName)) {
                    findings.add(new LintFinding(UNKNOWN_SLOT, ERROR, source,
                            "view " + spec.id() + ": unknown slot " + slotName + " (a "
                                    + spec.view() + " view offers " + allowed + ")"));
                    continue;
                }
                // Slot templates resolve against the view document's own directory, then
                // templates/ — never a referencing route's (docs/view-composition.md wave 1).
                String ref = spec.slots().get(slotName);
                int separator = ref.indexOf("::");
                String template = separator < 1 ? ref : ref.substring(0, separator).trim();
                Path slotColocated = viewDir.resolve(template).normalize();
                Path slotFile = Files.isRegularFile(slotColocated)
                        ? slotColocated
                        : appHome.resolve("templates").resolve(template).normalize();
                if (separator < 1 || !Files.isRegularFile(slotFile)) {
                    findings.add(new LintFinding(INVALID_VIEW_BINDING, ERROR, source,
                            "view " + spec.id() + ": slot " + slotName + " reference " + ref
                                    + " does not resolve ('<template> :: <fragment>')"));
                }
            }
        }
        for (String root : List.of("web", "templates")) {
            Path tree = appHome.resolve(root);
            if (!Files.isDirectory(tree)) {
                continue;
            }
            try (var files = Files.walk(tree)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".view.yml"))
                        .filter(p -> !indexed.contains(p))
                        .sorted()
                        .forEach(file -> {
                            String source = appHome.relativize(file).toString()
                                    .replace('\\', '/');
                            try {
                                io.tesseraql.yaml.view.ViewSpec.parse(file);
                            } catch (io.tesseraql.core.error.TqlException ex) {
                                findings.add(new LintFinding(ex.code().toString(), ERROR,
                                        source, ex.getMessage()));
                            }
                        });
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    /**
     * A child/panel {@code source:} must name one of the route's {@code sources:}, or
     * {@code main} (TQL-VIEW-3308) — whatever arm a source declares, it publishes the
     * {@code {rows}} shape the view model reads.
     */
    private static boolean declaresViewSource(RouteDefinition definition, String source) {
        if (RouteDefinition.MAIN.equals(source)) {
            return true;
        }
        var sources = definition.sources();
        return sources != null && sources.containsKey(source);
    }

    /** A form view's action route exists, declares inputs, and covers every fields: entry. */
    private void lintFormView(AppManifest manifest, String source,
            io.tesseraql.yaml.view.ViewSpec spec, List<LintFinding> findings) {
        RouteFile action = null;
        for (RouteFile candidate : manifest.routes()) {
            if ("POST".equalsIgnoreCase(candidate.httpMethod())
                    && candidate.urlPath().equals(spec.action())) {
                action = candidate;
                break;
            }
        }
        if (action == null) {
            findings.add(new LintFinding(INVALID_VIEW_ACTION, ERROR, source,
                    "view " + spec.id() + ": action " + spec.action()
                            + " matches no POST route"));
            return;
        }
        var inputs = action.definition().input();
        if (inputs == null || inputs.isEmpty()) {
            findings.add(new LintFinding(INVALID_VIEW_ACTION, ERROR, source,
                    "view " + spec.id() + ": action route " + action.definition().id()
                            + " declares no input: block to derive fields from"));
            return;
        }
        for (io.tesseraql.yaml.view.ViewSpec.Field field : spec.fields()) {
            if (!inputs.containsKey(field.name())) {
                findings.add(new LintFinding(ACTION_FIELD_NOT_DECLARED, ERROR, source,
                        "view " + spec.id() + ": field " + field.name()
                                + " is not declared by the action route's input: block"));
            }
            if (field.widget() != null
                    && !io.tesseraql.yaml.view.ViewSpec.WIDGETS.contains(field.widget())) {
                findings.add(new LintFinding(UNKNOWN_WIDGET, ERROR, source,
                        "view " + spec.id() + ": unknown widget " + field.widget()
                                + " (known: " + io.tesseraql.yaml.view.ViewSpec.WIDGETS + ")"));
            }
        }
    }

    /**
     * An L2 pattern override must carry the pattern's fragment signature so it stays compatible
     * with fragment-level composition (docs/declarative-views.md; warning, not error — the whole
     * file still renders today).
     */
    private void lintViewOverrides(Path appHome, List<LintFinding> findings) {
        Path overrides = appHome.resolve("templates").resolve("tql").resolve("view");
        if (!java.nio.file.Files.isDirectory(overrides)) {
            return;
        }
        try (var files = java.nio.file.Files.list(overrides)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".html"))
                    .sorted().toList()) {
                String name = file.getFileName().toString();
                String expected = name.startsWith("field")
                        ? "th:fragment=\"field(f)\""
                        : "th:fragment=\"view(v)\"";
                String content = java.nio.file.Files.readString(file);
                if (!content.contains(expected)) {
                    findings.add(new LintFinding(INVALID_VIEW_PATTERN_OVERRIDE, WARNING,
                            appHome.relativize(file).toString().replace('\\', '/'),
                            "view pattern override lacks the expected " + expected
                                    + " signature (docs/declarative-views.md)"));
                }
            }
        } catch (java.io.IOException ex) {
            findings.add(
                    new LintFinding(INVALID_VIEW_PATTERN_OVERRIDE, WARNING, "templates/tql/view",
                            "view pattern overrides could not be read: " + ex.getMessage()));
        }
    }
}
