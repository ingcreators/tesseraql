package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ApplicationName;
import io.tesseraql.yaml.app.PolicyCodes;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;
import java.util.Map;

/**
 * An application's permission codes carry its own name as their first segment
 * (docs/stack-shells.md structural decision 1).
 *
 * <p>The rule binds the codes the application's declared policies reference —
 * {@code tesseraql.security.policies.*.anyOf[].permission} — because those are the strings the
 * identity store grants: two applications both inventing {@code approve} would silently share one
 * grant. Policy <em>ids</em> stay free; they are local to this configuration and never reach the
 * store. Role rules stay free too: roles are the deployment's vocabulary, and a deployment role
 * may bundle any codes it likes.
 *
 * <p>The boot refusal in {@code SecurityConfigFactory} carries the same message via
 * {@link PolicyCodes#violation}, so the fence holds for a configuration that never linted.
 */
final class PolicyCodeRules implements LintRule {

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        String appName = manifest.config().getString("tesseraql.app.name")
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .filter(name -> ApplicationName.segmentViolation(name) == null)
                .orElse(null);
        if (appName == null) {
            // ApplicationNameRules reports the missing or unsafe name; codes cannot be judged
            // against a namespace that does not exist yet.
            return;
        }
        if (!(manifest.config()
                .navigate("tesseraql.security.policies") instanceof Map<?, ?> policies)) {
            return;
        }
        policies.forEach((id, spec) -> {
            if (spec instanceof Map<?, ?> map && map.get("anyOf") instanceof List<?> anyOf) {
                for (Object element : anyOf) {
                    if (element instanceof Map<?, ?> rule && rule.get("permission") != null) {
                        String code = String.valueOf(rule.get("permission"));
                        String violation = PolicyCodes.violation(appName, code);
                        if (violation != null) {
                            findings.add(new LintFinding(PolicyCodes.OUTSIDE_NAMESPACE.toString(),
                                    ERROR, "config", "Policy '" + id + "': " + violation));
                        }
                    }
                }
            }
        });
    }
}
