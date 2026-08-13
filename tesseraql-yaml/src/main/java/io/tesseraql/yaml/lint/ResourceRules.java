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
 * Application-declared MCP resources.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ResourceRules implements LintRule {

    private static final String RESOURCE_NOT_READ_ONLY = "TQL-MCP-1003";

    private static final String RESOURCE_WITHOUT_URI = "TQL-MCP-1004";

    private static final String RESOURCE_DECLARES_INPUT = "TQL-MCP-1006";

    private static final String RESOURCE_WITHOUT_DESCRIPTION = "TQL-MCP-1005";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (io.tesseraql.yaml.manifest.ResourceFile resource : manifest.resources()) {
            lintResource(appHome, manifest.config(), resource, findings);
        }
    }

    /**
     * Lints an application-declared MCP resource (roadmap Phase 24): it is read-only (the
     * {@code query-json} recipe, query-mode SQL), declares a {@code uri} the client reads, takes no
     * {@code input:} (a resource is addressed only by its uri), its SQL file exists, and its
     * referenced policy is defined. A missing description is a warning: it is the hint the model
     * uses to decide whether to attach the resource as context.
     */
    void lintResource(Path appHome, AppConfig config,
            io.tesseraql.yaml.manifest.ResourceFile resource, List<LintFinding> findings) {
        RouteDefinition definition = resource.definition();
        String source = appHome.relativize(resource.source()).toString().replace('\\', '/');
        UnknownKeyRules.lintUnknownKeys(context, appHome, resource.source(), RouteDefinition.class,
                Set.of("description", "uri", "mimeType"), findings);

        boolean write = !"query-json".equals(definition.recipe())
                || (definition.main() != null
                        && "update".equals(definition.main().effectiveMode()));
        if (write) {
            findings.add(new LintFinding(RESOURCE_NOT_READ_ONLY, ERROR, source,
                    "MCP resource '" + definition.id() + "' must be read-only: use the query-json"
                            + " recipe with query-mode SQL"));
        }
        if (resource.uri() == null || resource.uri().isBlank()) {
            findings.add(new LintFinding(RESOURCE_WITHOUT_URI, ERROR, source,
                    "MCP resource '" + definition.id() + "' must declare a uri: it is the address"
                            + " the client reads the resource by"));
        }
        if (!definition.input().isEmpty()) {
            findings.add(new LintFinding(RESOURCE_DECLARES_INPUT, ERROR, source,
                    "MCP resource '" + definition.id() + "' must not declare input: a resource is"
                            + " addressed only by its uri and takes no arguments"));
        }
        if (resource.description() == null || resource.description().isBlank()) {
            findings.add(new LintFinding(RESOURCE_WITHOUT_DESCRIPTION, WARNING, source,
                    "MCP resource '" + definition.id() + "' has no description; it is the hint the"
                            + " model uses to decide whether to attach the resource"));
        }
        if (definition.main() != null && !definition.main().isContract()
                && definition.main().file() != null
                && !Files.isRegularFile(
                        resource.source().getParent().resolve(definition.main().file()))) {
            findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                    "Referenced SQL file is missing: " + definition.main().file()));
        }
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank() && !DocumentRules.policyDefined(config, policy)) {
            findings.add(new LintFinding(LintCodes.UNDEFINED_POLICY, WARNING, source,
                    "MCP resource references undefined policy '" + policy + "' (deny by default)"));
        }
        DocumentRules.lintDatasource(context, config, resource.source(), definition, source,
                findings);
    }
}
