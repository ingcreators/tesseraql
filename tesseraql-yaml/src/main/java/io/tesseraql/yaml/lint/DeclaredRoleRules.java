package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ApplicationName;
import io.tesseraql.yaml.app.DeclaredRoles;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * An application's declared roles carry its own name and bundle only its own codes
 * (docs/application-roles.md structural decision 1). The boot refusal in the runtime carries
 * the same messages via {@link DeclaredRoles#violations}, so the fence holds for a
 * configuration that never linted.
 */
final class DeclaredRoleRules implements LintRule {

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        String appName = manifest.config().getString("tesseraql.app.name")
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .filter(name -> ApplicationName.segmentViolation(name) == null)
                .orElse(null);
        if (appName == null) {
            return;
        }
        List<DeclaredRoles.DeclaredRole> roles = DeclaredRoles
                .parse(manifest.config().navigate("tesseraql.security.roles"));
        for (String violation : DeclaredRoles.violations(appName, roles)) {
            findings.add(new LintFinding(DeclaredRoles.INVALID.toString(), ERROR, "config",
                    violation));
        }
    }
}
