package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * Org-unit configuration.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class OrgUnitRules implements LintRule {

    private static final String INVALID_ORG_UNIT_MODE = "TQL-SCOPE-3020";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintOrgUnitConfig(manifest.config(), findings);
    }

    /** Validates org-unit configuration (roadmap Phase 29 slice 2): a known {@code mode}. */
    void lintOrgUnitConfig(AppConfig config, List<LintFinding> findings) {
        String mode = config.getString("tesseraql.orgunit.mode").orElse(null);
        if (mode != null && !"managed".equalsIgnoreCase(mode) && !"app".equalsIgnoreCase(mode)) {
            findings.add(new LintFinding(INVALID_ORG_UNIT_MODE, ERROR, "config",
                    "tesseraql.orgunit.mode must be 'managed' or 'app', not '" + mode + "'"));
        }
    }
}
