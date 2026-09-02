package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.Locale;
import java.util.Map;

/**
 * Catalog lookup for the text the rendering surfaces write themselves: exact language tag, then
 * the bare language, then the framework's own fallback sentence.
 *
 * <p>One copy because the walk has to be identical everywhere — a surface that stopped at the
 * exact tag would silently ignore an app's {@code ja.yml} for a {@code ja-JP} request while its
 * neighbours honoured it.
 */
final class ViewMessages {

    private ViewMessages() {
    }

    /** The message for {@code key}, or {@code fallback} when no layer of the catalog has it. */
    static String text(MessageCatalog catalog, Locale locale, String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        String exact = catalog.forLocale(locale.toLanguageTag()).get(key);
        if (exact != null) {
            return exact;
        }
        String language = catalog.forLocale(locale.getLanguage()).get(key);
        return language != null ? language : fallback;
    }

    /** The message with its <code>{name}</code> placeholders filled. */
    static String text(MessageCatalog catalog, Locale locale, String key, String fallback,
            Map<String, ?> values) {
        return MessageCatalog.interpolate(text(catalog, locale, key, fallback), values);
    }
}
