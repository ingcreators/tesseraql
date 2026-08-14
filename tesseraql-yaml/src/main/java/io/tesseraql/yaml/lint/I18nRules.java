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
 * Message catalogs and the message keys documents reference.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class I18nRules implements LintRule {

    private static final String UNREADABLE_MESSAGE_CATALOG = "TQL-YAML-1007";

    private static final String DECLARED_LOCALE_WITHOUT_CATALOG = "TQL-YAML-1103";

    // A catalog is behind the default locale — the keys it misses fall back untranslated.
    private static final String TRANSLATION_GAP = "TQL-YAML-1008";

    private static final String UNRESOLVED_MESSAGE_KEY = "TQL-FIELD-2005";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintI18n(context.appHome(), manifest, findings);
    }

    /**
     * Statically checks the app's message catalogs (roadmap Phase 22) when a {@code messages/}
     * directory exists: catalog files parse and carry valid BCP-47 names (TQL-YAML-1007), every
     * locale declared in {@code tesseraql.i18n.locales} has catalog entries to read
     * (TQL-YAML-1103), translation gaps against the default locale surface per catalog
     * (TQL-YAML-1008), and every validation-rule / constraint-mapping message key resolves in
     * the default locale (TQL-FIELD-2005; {@code tql.*} keys resolve through the framework's
     * built-in catalog and are skipped).
     */
    void lintI18n(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        AppConfig config = manifest.config();
        String defaultTag = java.util.Locale.forLanguageTag(
                config.getString("tesseraql.i18n.defaultLocale").orElse("en")).toLanguageTag();
        boolean hasCatalog = Files.isDirectory(appHome.resolve("messages"));
        io.tesseraql.yaml.i18n.MessageCatalog catalog;
        if (hasCatalog) {
            try {
                catalog = io.tesseraql.yaml.i18n.MessageCatalog.load(appHome.resolve("messages"));
            } catch (io.tesseraql.core.error.TqlException ex) {
                findings.add(new LintFinding(UNREADABLE_MESSAGE_CATALOG, ERROR, "messages",
                        ex.getMessage()));
                return;
            }
        } else {
            // No catalog files to check, but a declared message: key must still resolve — with no
            // messages/ every non-tql. key falls through to the raw key at runtime (the user sees
            // 'order.qty.tooLarge' as the error text), which the reference loop below now reports.
            catalog = io.tesseraql.yaml.i18n.MessageCatalog.empty();
            lintMessageKeyReferences(appHome, manifest, catalog, defaultTag, findings);
            return;
        }

        Object declared = config.navigate("tesseraql.i18n.locales");
        if (declared instanceof List<?> tags) {
            for (Object tag : tags) {
                String normalized = java.util.Locale
                        .forLanguageTag(String.valueOf(tag)).toLanguageTag();
                if (!normalized.equals(defaultTag)
                        && catalog.forLocale(normalized).isEmpty()) {
                    findings.add(
                            new LintFinding(DECLARED_LOCALE_WITHOUT_CATALOG, WARNING, "messages",
                                    "Declared locale '" + tag + "' has no messages/" + normalized
                                            + ".yml catalog"));
                }
            }
        }

        java.util.Map<String, String> defaults = catalog.forLocale(defaultTag);
        for (String tag : catalog.tags()) {
            if (tag.equals(defaultTag)) {
                continue;
            }
            List<String> missing = defaults.keySet().stream()
                    .filter(key -> catalog.resolve(tag, key) == null)
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                findings.add(new LintFinding(TRANSLATION_GAP, WARNING,
                        "messages",
                        "Catalog '" + tag + "' is missing " + missing.size()
                                + " key(s) present in the default locale '" + defaultTag
                                + "' (first: " + missing.get(0) + ")"));
            }
        }

        lintMessageKeyReferences(appHome, manifest, catalog, defaultTag, findings);
    }

    /**
     * Checks every declared {@code message:} key (validate rules, constraint mappings) resolves in
     * the catalog. Runs whether or not a {@code messages/} tree exists — an app with no catalog but
     * a {@code message:} reference otherwise gets no diagnostic and the raw key leaks to the user.
     */
    private void lintMessageKeyReferences(Path appHome, AppManifest manifest,
            io.tesseraql.yaml.i18n.MessageCatalog catalog, String defaultTag,
            List<LintFinding> findings) {
        for (RouteFile route : manifest.routes()) {
            String source = appHome.relativize(route.source()).toString().replace('\\', '/');
            route.definition().validate().forEach((id, rule) -> lintMessageKey(catalog,
                    defaultTag, rule.message(), "Validation rule '" + id + "'", source,
                    findings));
            if (route.definition().errors() != null) {
                route.definition().errors().constraints()
                        .forEach((constraint, mapping) -> lintMessageKey(catalog, defaultTag,
                                mapping.message(), "Constraint mapping '" + constraint + "'",
                                source, findings));
            }
        }
    }

    /** Warns when a declared message key has no default-locale text to render. */
    private void lintMessageKey(io.tesseraql.yaml.i18n.MessageCatalog catalog, String defaultTag,
            String key, String owner, String source, List<LintFinding> findings) {
        if (key == null || key.isBlank() || key.startsWith("tql.")) {
            return;
        }
        if (catalog.resolve(defaultTag, key) == null) {
            findings.add(new LintFinding(UNRESOLVED_MESSAGE_KEY, WARNING, source,
                    owner + " declares message key '" + key + "' that no messages/" + defaultTag
                            + ".yml entry resolves"));
        }
    }
}
