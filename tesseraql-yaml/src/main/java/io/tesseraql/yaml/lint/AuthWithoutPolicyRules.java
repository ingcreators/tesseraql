package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SecuritySpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A document that authenticates its caller and then authorizes nothing
 * (docs/audit-hardening.md Decision 1).
 *
 * <p>{@code RouteCompiler.applySecurity} emits the authorize step only when {@code policy:} is
 * non-blank, so {@code auth:} without {@code policy:} establishes who the caller is and then lets
 * anyone through. In the YAML that is indistinguishable from a governed route — the block is
 * present, it names a real authentication mode, and nothing about it reads as "open".
 *
 * <p>A warning rather than an error, because the shape is legitimate: a route whose whole
 * authorization is "any authenticated caller" is a real design, and so is one that authorizes in
 * SQL through a scope directive. The finding is worth making because the alternative — the author
 * believing a policy is in force — is silent.
 *
 * <p><b>MCP primitives are out of scope here</b>, deliberately. {@code docs/prompt-as-recipe.md}
 * argues that an MCP read primitive need not declare a policy, which is a different shape from
 * authenticate-then-nothing, and MCP primitives get their own floor and their own warning with the
 * MCP security defaults. Firing on them from here would produce the finding everybody learns to
 * scroll past.
 */
final class AuthWithoutPolicyRules implements LintRule {

    private static final String AUTH_WITHOUT_POLICY = "TQL-SEC-4049";

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        List<Map.Entry<Path, RouteDefinition>> documents = new java.util.ArrayList<>();
        manifest.routes().forEach(r -> documents.add(Map.entry(r.source(), r.definition())));
        manifest.consumers().forEach(c -> documents.add(Map.entry(c.source(), c.definition())));
        for (Map.Entry<Path, RouteDefinition> document : documents) {
            SecuritySpec security = document.getValue().security();
            if (security == null || security.auth() == null || "public".equals(security.auth())) {
                continue;
            }
            if (security.policy() != null && !security.policy().isBlank()) {
                continue;
            }
            String source = context.appHome().relativize(document.getKey()).toString()
                    .replace('\\', '/');
            findings.add(new LintFinding(AUTH_WITHOUT_POLICY, WARNING, source,
                    "'" + document.getValue().id() + "' declares auth: " + security.auth()
                            + " and no policy, so it identifies the caller and then authorizes"
                            + " nothing; declare a policy, or auth: public if the route is open"));
        }
    }
}
