package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Code catalogs and their language columns.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1). The
 * export-locale rule that lived here until 0.18.0 guarded a capability no export has — the
 * export chains publish declared source names only, so a catalog never reaches an export
 * template — and is deleted (docs/lookups.md, decision 12 as built).
 */
final class CatalogLocaleRules implements LintRule {

    private static final String CATALOG_FILE_OUTSIDE_CATALOGS = "TQL-FIELD-4621";

    private static final String CATALOG_LANGUAGE_IN_SINGLE_LOCALE_APP = "TQL-FIELD-4619";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        Path appHome = context.appHome();
        lintCatalogLanguages(appHome, manifest.config(), findings);
        lintCatalogFiles(appHome, findings);
    }

    /**
     * A {@code file:} catalog whose SQL is not there (docs/lookups.md, decision 13).
     *
     * <p>The file is read at the first load, which is the first request that renders a code —
     * so without this the failure surfaces as a page that lost its names, at runtime, on
     * whichever screen happened to ask first.
     */
    void lintCatalogFiles(Path appHome, List<LintFinding> findings) {
        io.tesseraql.yaml.catalog.Catalogs.load(appHome).all().forEach((name, spec) -> {
            if (spec.file() == null || spec.file().isBlank()) {
                return;
            }
            boolean inside = io.tesseraql.core.files.ConfinedPath
                    .under(appHome.resolve("catalogs"))
                    .resolve(spec.file()).filter(Files::isRegularFile).isPresent();
            if (!inside) {
                findings.add(new LintFinding(CATALOG_FILE_OUTSIDE_CATALOGS, ERROR, "catalogs/",
                        "Catalog '" + name + "': file '" + spec.file() + "' is not a SQL file"
                                + " under catalogs/"));
            }
        });
    }

    /**
     * A catalog that carries per-language names in an app that negotiates one locale
     * (docs/lookups.md, decision 12).
     *
     * <p>The language a catalog answers in is the surface's resolved locale, and a request can
     * only resolve to a locale the app supports. So a {@code language:} column in an app whose
     * {@code tesseraql.i18n.locales} is a single entry has rows nothing can ever ask for: every
     * request falls back to the default language, and the translations look broken rather than
     * unreachable. A warning, not an error — the master may be shared with a system that does
     * serve the other languages.
     */
    void lintCatalogLanguages(Path appHome, AppConfig config, List<LintFinding> findings) {
        io.tesseraql.yaml.catalog.Catalogs catalogs = io.tesseraql.yaml.catalog.Catalogs
                .load(appHome);
        // A declared locale the runtime cannot serve is I18nRules' finding; the settings
        // would refuse to build here.
        if (catalogs.isEmpty()
                || !io.tesseraql.yaml.i18n.I18nSettings.declarationProblems(config).isEmpty()) {
            return;
        }
        io.tesseraql.yaml.i18n.I18nSettings i18n = io.tesseraql.yaml.i18n.I18nSettings
                .from(config, appHome);
        if (i18n.supportedTags().size() > 1) {
            return;
        }
        catalogs.all().forEach((name, spec) -> {
            if (spec.language() == null || spec.language().isBlank()) {
                return;
            }
            findings.add(
                    new LintFinding(CATALOG_LANGUAGE_IN_SINGLE_LOCALE_APP, WARNING, "catalogs/",
                            "Catalog '" + name + "' declares language: " + spec.language()
                                    + " but the app supports one locale (" + i18n.defaultTag()
                                    + ") — every request resolves to it, so the other languages"
                                    + " never render; declare tesseraql.i18n.locales"));
        });
    }
}
