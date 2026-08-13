package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * MCP Apps UI resources ({@code ui://}).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class UiResourceRules implements LintRule {

    private static final String UI_RESOURCE_RECIPE_UNSUPPORTED = "TQL-MCP-1008";

    private static final String UI_RESOURCE_WITHOUT_URI = "TQL-MCP-1009";

    private static final String UI_RESOURCE_DECLARES_INPUT = "TQL-MCP-1011";

    private static final String UI_RESOURCE_WITHOUT_DESCRIPTION = "TQL-MCP-1010";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            lintUiResource(appHome, manifest.config(), ui, findings);
        }
    }

    /** Recipes an MCP Apps UI resource may use - both render HTML (roadmap Phase 24). */
    private static final Set<String> KNOWN_UI_RECIPES = Set.of("query-html", "page");

    /** The MCP Apps uri scheme a UI resource is addressed by (SEP-1865). */
    private static final String UI_SCHEME = "ui://";

    /**
     * Lints an application-declared MCP Apps UI resource (roadmap Phase 24): it renders HTML (the
     * {@code query-html} or {@code page} recipe), declares a {@code ui://} uri the client reads and
     * tools link to, takes no {@code input:} (a UI resource is addressed only by its uri), its SQL
     * file exists, and its referenced policy is defined. A missing description is a warning: it is
     * the hint the model uses to decide whether to surface the UI.
     */
    void lintUiResource(Path appHome, AppConfig config,
            io.tesseraql.yaml.manifest.UiResourceFile ui, List<LintFinding> findings) {
        RouteDefinition definition = ui.definition();
        String source = appHome.relativize(ui.source()).toString().replace('\\', '/');
        UnknownKeyRules.lintUnknownKeys(context, appHome, ui.source(), RouteDefinition.class,
                Set.of("description", "uri", "ui"), findings);

        if (!KNOWN_UI_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding(UI_RESOURCE_RECIPE_UNSUPPORTED, ERROR, source,
                    "MCP UI resource '" + definition.id() + "' has recipe '" + definition.recipe()
                            + "'; a UI resource renders HTML - use query-html or page"));
        }
        if (ui.uri() == null || !ui.uri().startsWith(UI_SCHEME)) {
            findings.add(new LintFinding(UI_RESOURCE_WITHOUT_URI, ERROR, source,
                    "MCP UI resource '" + definition.id() + "' must declare a ui:// uri: it is the"
                            + " address the client reads and a tool links to"));
        }
        if (!definition.input().isEmpty()) {
            findings.add(new LintFinding(UI_RESOURCE_DECLARES_INPUT, ERROR, source,
                    "MCP UI resource '" + definition.id()
                            + "' must not declare input: a UI resource"
                            + " is addressed only by its uri and takes no arguments"));
        }
        if (ui.description() == null || ui.description().isBlank()) {
            findings.add(new LintFinding(UI_RESOURCE_WITHOUT_DESCRIPTION, WARNING, source,
                    "MCP UI resource '" + definition.id() + "' has no description; it is the hint"
                            + " the model uses to decide whether to surface the UI"));
        }
        if (definition.main() != null && !definition.main().isContract()
                && definition.main().file() != null
                && !Files
                        .isRegularFile(ui.source().getParent().resolve(definition.main().file()))) {
            findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                    "Referenced SQL file is missing: " + definition.main().file()));
        }
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank() && !DocumentRules.policyDefined(config, policy)) {
            findings.add(new LintFinding(LintCodes.UNDEFINED_POLICY, WARNING, source,
                    "MCP UI resource references undefined policy '" + policy
                            + "' (deny by default)"));
        }
        DocumentRules.lintDatasource(context, config, ui.source(), definition, source, findings);
    }
}
