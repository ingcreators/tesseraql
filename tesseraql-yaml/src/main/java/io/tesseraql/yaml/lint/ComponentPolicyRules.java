package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * The component allow-list policy.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ComponentPolicyRules implements LintRule {

    private static final String COMPONENT_ALLOWLIST_WITHOUT_EFFECT = "TQL-SEC-4139";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintComponentPolicy(manifest.config(), findings);
    }

    /**
     * Lints the Camel component policy (docs/component-guard.md): a config entry that tries to
     * re-allow a baseline-denied component is ignored by the guard — surfacing the attempt is
     * the difference between "I widened the posture" and reality.
     */
    void lintComponentPolicy(AppConfig config, List<LintFinding> findings) {
        io.tesseraql.yaml.config.ComponentPolicy policy = io.tesseraql.yaml.config.ComponentPolicy
                .from(config);
        for (String name : policy.allowed()) {
            if (io.tesseraql.yaml.config.ComponentPolicy.BASELINE_DENIED.contains(name)) {
                findings.add(new LintFinding(COMPONENT_ALLOWLIST_WITHOUT_EFFECT, WARNING, "config",
                        "tesseraql.camel.components.allowed lists '" + name + "', but the"
                                + " built-in baseline refuses it — the entry has no effect"));
            }
        }
    }
}
