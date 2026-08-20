package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Code catalogs, their language columns, and export locales.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class CatalogLocaleRules implements LintRule {

    private static final String EXPORT_WITHOUT_LOCALE = "TQL-FIELD-4622";

    private static final String CATALOG_FILE_OUTSIDE_CATALOGS = "TQL-FIELD-4621";

    private static final String CATALOG_LANGUAGE_IN_SINGLE_LOCALE_APP = "TQL-FIELD-4619";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        Path appHome = context.appHome();
        lintCatalogLanguages(appHome, manifest.config(), findings);
        lintCatalogFiles(appHome, findings);
        lintExportLocale(appHome, manifest, findings);
    }

    /**
     * An export that can render a code but cannot name a locale (docs/lookups.md, decision 12).
     *
     * <p>The per-surface locale table exists because "the report came out in English because
     * the server's locale was" is this feature's characteristic failure. A request negotiates
     * its locale; an export does not have one to negotiate — it is generated for a file, often
     * on a schedule, and read by someone who never made the request. So the export declares its
     * locale, and an app whose catalogs carry more than one language must not leave that
     * declaration to a default.
     *
     * <p>Scoped to apps with a multilingual catalog: a single-language app has one answer
     * whatever the locale, and demanding a declaration there would be ceremony.
     */
    void lintExportLocale(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        boolean multilingual = io.tesseraql.yaml.catalog.Catalogs.load(appHome).all().values()
                .stream().anyMatch(spec -> spec.language() != null && !spec.language().isBlank());
        if (!multilingual
                || manifest.config().getString("tesseraql.files.locale").isPresent()) {
            return;
        }
        for (RouteFile route : manifest.routes()) {
            io.tesseraql.yaml.model.ExportSpec spec = route.definition().fileExport();
            if (spec == null || (spec.locale() != null && !spec.locale().isBlank())) {
                continue;
            }
            findings.add(new LintFinding(EXPORT_WITHOUT_LOCALE, ERROR, route.source().toString(),
                    "Export '" + route.definition().id() + "' declares no locale:, and the app"
                            + " has catalogs with per-language names — declare the export's"
                            + " locale: or tesseraql.files.locale; an export has no request to"
                            + " negotiate one from"));
        }
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
            Path dir = appHome.resolve("catalogs");
            Path file = dir.resolve(spec.file()).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
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
        if (catalogs.isEmpty()) {
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
