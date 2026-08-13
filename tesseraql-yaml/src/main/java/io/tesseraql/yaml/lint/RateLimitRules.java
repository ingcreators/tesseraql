package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;

/**
 * A route's rate-limit scope.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class RateLimitRules {

    private RateLimitRules() {
    }

    /** rateLimit.scope is {@code node} or {@code cluster} (docs/deployment.md) — TQL-YAML-1023. */
    static void lintRateLimitScope(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var admission = definition.admission();
        if (admission == null || admission.rateLimit() == null) {
            return;
        }
        String scope = admission.rateLimit().scope();
        if (scope != null && !"node".equals(scope) && !"cluster".equals(scope)) {
            findings.add(new LintFinding("TQL-YAML-1023", "error", source,
                    "rateLimit.scope must be 'node' or 'cluster', got '" + scope + "'"));
        }
    }
}
