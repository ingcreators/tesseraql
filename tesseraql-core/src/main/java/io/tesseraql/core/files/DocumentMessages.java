package io.tesseraql.core.files;

import java.util.function.Function;

/**
 * The message texts a rendered document reads through {@code #{key}}: the application's
 * catalogs over the framework's built-ins, in the export's locale
 * (docs/printable-documents.md). The codec asks once per document for the locale it renders
 * in and resolves every key of the template from the answer, so a catalog is read once per
 * render and never once per key. A key no catalog holds answers {@code null}, which the
 * template engine shows as its absent-message marker — a translation gap stays visible in the
 * document, exactly as it does on a page.
 */
@FunctionalInterface
public interface DocumentMessages {

    /** The texts for one BCP 47 language tag; each lookup answers {@code null} for an unknown key. */
    Function<String, String> forLocale(String languageTag);
}
