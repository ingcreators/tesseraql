package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SecuritySpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An MCP read primitive that nothing gates at all
 * (docs/audit-hardening.md Decision 2, open question 4).
 *
 * <p>A write tool has had a floor since the MCP surface shipped: {@code TQL-MCP-4030} refuses one
 * with no policy, because an agent must not mutate data without authorization. A read primitive had
 * none — no declared {@code security:}, and no path defaults either, since MCP documents are loaded
 * into their own collections and never reach {@code applySecurityDefaults}. So the answer to "who
 * may read this?" was "anybody who can reach the endpoint", and nothing said so.
 *
 * <p>A warning rather than an error, and the precedent is deliberate: {@code docs/prompt-as-recipe.md}
 * argues that an MCP read primitive need not declare a policy, and that argument stands. Discovery
 * is open by design and a genuinely public read is a real design. What is worth saying out loud is
 * the case where <em>nothing</em> is in force — not the declaration, not the defaults block — since
 * that is indistinguishable in the YAML from a primitive somebody meant to govern.
 *
 * <p>It stays quiet as soon as anything applies, which is what keeps it from becoming the finding
 * everybody learns to scroll past: declare {@code security:} on the document, or declare
 * {@code tesseraql.security.defaults.mcp}, and it is silent.
 */
final class McpReadFloorRules implements LintRule {

    private static final String MCP_READ_WITHOUT_FLOOR = "TQL-MCP-4261";

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        List<Map.Entry<Path, RouteDefinition>> primitives = new ArrayList<>();
        manifest.tools().forEach(tool -> primitives.add(
                Map.entry(tool.source(), tool.definition())));
        manifest.resources().forEach(resource -> primitives.add(
                Map.entry(resource.source(), resource.definition())));
        manifest.prompts().forEach(prompt -> primitives.add(
                Map.entry(prompt.source(), prompt.definition())));

        for (Map.Entry<Path, RouteDefinition> primitive : primitives) {
            RouteDefinition definition = primitive.getValue();
            if (writes(definition) || governed(definition.security())) {
                continue;
            }
            String source = context.appHome().relativize(primitive.getKey()).toString()
                    .replace('\\', '/');
            findings.add(new LintFinding(MCP_READ_WITHOUT_FLOOR, WARNING, source,
                    "MCP primitive '" + definition.id() + "' declares no security: and no"
                            + " tesseraql.security.defaults.mcp supplies one, so any caller that"
                            + " reaches the endpoint can read it"));
        }
    }

    /** A write primitive is TQL-MCP-4030's, which is an error rather than this warning. */
    private static boolean writes(RouteDefinition definition) {
        return "command-json".equals(definition.recipe())
                || (definition.main() != null
                        && "update".equals(definition.main().effectiveMode()));
    }

    /**
     * Whether anything gates the call.
     *
     * <p>The defaults block has already been resolved into the document by the time linting runs,
     * so this reads the effective spec rather than asking the config a second question — which is
     * also why an app that declares the block sees nothing from this rule.
     */
    private static boolean governed(SecuritySpec security) {
        if (security == null) {
            return false;
        }
        boolean authenticates = security.auth() != null && !security.auth().isBlank()
                && !"public".equals(security.auth());
        boolean authorizes = security.policy() != null && !security.policy().isBlank();
        return authenticates || authorizes;
    }
}
