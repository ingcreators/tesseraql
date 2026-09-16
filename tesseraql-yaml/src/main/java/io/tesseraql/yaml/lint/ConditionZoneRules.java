package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.config.ConditionZone;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * The app-wide {@code tesseraql.security.conditions.zone} literal a role's hours conditions
 * are judged in (docs/access-governance.md structural decision 8): judged by the same zone
 * predicate as a declared export zone, here as a finding and in the runtime as the boot
 * refusal. A value carrying an unresolved placeholder is the deployment's to supply and is
 * skipped, as the files keys are.
 */
final class ConditionZoneRules implements LintRule {

    private static final String INVALID = ConditionZone.INVALID.toString();

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        ConditionZone.problem(manifest.config()).ifPresent(problem -> findings.add(
                new LintFinding(INVALID, ERROR, "config", problem)));
    }
}
