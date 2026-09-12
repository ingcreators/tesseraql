package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * The app-wide {@code tesseraql.files.locale} / {@code tesseraql.files.timezone} literals every
 * export and import of the app falls back to (docs/export-declarations.md): judged by the same
 * predicate as a route's declaration, here as a finding and in the compiler as the refusal.
 * A value carrying an unresolved placeholder is the deployment's to supply and is skipped.
 */
final class FilesConfigRules implements LintRule {

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        ExportRules.report(null, null, null, ExportDeclarations.configViolations(
                ExportRules.appName(manifest.config()), manifest.config()), "config", findings);
    }
}
