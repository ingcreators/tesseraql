package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code config/preferences.yml}.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class PreferenceRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintPreferences(context.appHome(), findings);
    }

    /**
     * Validates {@code config/preferences.yml} (roadmap Phase 48 slice 5) by loading it the
     * way the runtime does: TQL-YAML-1030 parse/key/duplicate, 1031 unknown type, 1032 choice
     * without options, 1033 default outside the acceptable values.
     */
    void lintPreferences(Path appHome, List<LintFinding> findings) {
        try {
            io.tesseraql.yaml.account.PreferencesSpec.load(appHome);
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(new LintFinding(ex.code().toString(), ERROR,
                    "config/preferences.yml", ex.getMessage()));
        }
    }
}
