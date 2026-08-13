package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Application-declared MCP tools.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ToolRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            lintTool(appHome, manifest.config(), tool, findings);
        }
    }

    /** Recipes an application-declared MCP tool may use (roadmap Phase 24 follow-on). */
    private static final Set<String> KNOWN_TOOL_RECIPES = Set.of("query-json", "command-json");

    /**
     * Lints an application-declared MCP tool (roadmap Phase 24 follow-on): its recipe is a tool
     * recipe, its SQL files exist, its referenced policy is defined, and - deny by default - a write
     * tool declares an authorization policy, since an AI agent must not mutate data unauthorized. A
     * missing description is a warning: it is the hint the model uses to decide when to call.
     */
    void lintTool(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.ToolFile tool,
            List<LintFinding> findings) {
        RouteDefinition definition = tool.definition();
        String source = appHome.relativize(tool.source()).toString().replace('\\', '/');
        // mcp documents reuse the route record plus loader-read keys; without this a typo'd
        // securty: on a tool was dropped in silence while every other surface flagged it.
        UnknownKeyRules.lintUnknownKeys(context, appHome, tool.source(), RouteDefinition.class,
                Set.of("description", "ui"), findings);

        if (!KNOWN_TOOL_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-MCP-1001", "error", source,
                    "MCP tool '" + definition.id() + "' has recipe '" + definition.recipe()
                            + "'; only query-json and command-json are supported"));
        }
        if (tool.description() == null || tool.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1002", "warning", source,
                    "MCP tool '" + definition.id() + "' has no description; it is the hint the"
                            + " model uses to decide when to call the tool"));
        }
        if (definition.main() != null && !definition.main().isContract()
                && definition.main().file() != null
                && !Files.isRegularFile(
                        tool.source().getParent().resolve(definition.main().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.main().file()));
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null
                    && !Files.isRegularFile(tool.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        definition.sources().forEach((name, query) -> {
            if (query.file() != null
                    && !Files.isRegularFile(tool.source().getParent().resolve(query.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Query '" + name + "' references a missing SQL file: " + query.file()));
            }
        });

        boolean write = "command-json".equals(definition.recipe())
                || (definition.main() != null
                        && "update".equals(definition.main().effectiveMode()));
        String policy = definition.security() == null ? null : definition.security().policy();
        if (write && (policy == null || policy.isBlank())) {
            findings.add(new LintFinding("TQL-MCP-4030", "error", source,
                    "Write MCP tool '" + definition.id() + "' must declare a security.policy: an AI"
                            + " agent must not mutate data without authorization (deny by default)"));
        }
        if (policy != null && !policy.isBlank() && !DocumentRules.policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP tool references undefined policy '" + policy + "' (deny by default)"));
        }
        // A tool's validate: runs through the same transactional pipeline a route's does.
        DocumentRules.lintValidation(context, tool.source(), definition, source, findings);
        LiveViewRules.lintEmit(definition, source, findings);
        DocumentRules.lintInvalidates(context, definition, source, findings);
        // emit: is a command-json route key. A tool may legally carry that recipe, so the route
        // check would pass it while the compiled tool pipeline broadcasts nothing — say so.
        if (!definition.emit().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1038", "error", source,
                    "emit: has no effect on an MCP tool — the compiled tool pipeline does not"
                            + " broadcast live-view topics"));
        }
        DocumentRules.lintDatasource(context, config, tool.source(), definition, source, findings);
        // An MCP tool's SQL is model-driven — its arguments come from an LLM — so it is the
        // highest-risk surface for embedded-variable injection, and it was the one not checked.
        DocumentRules.lintEmbeddedVariables(context, tool.source(), definition, source, findings);
        // A tool writes with the same bindings a command route does; the write-safety
        // and isolation nudges apply to it identically (docs/silent-tolerance.md K-e).
        DocumentRules.lintOptimisticLocking(context, tool.source(), definition, source, findings);
        DocumentRules.lintTenantPredicate(context, config, tool.source(), definition, source,
                findings);
    }
}
