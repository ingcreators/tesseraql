package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolved internationalization settings from the {@code tesseraql.i18n} configuration block
 * (roadmap Phase 22).
 *
 * <p>Example:
 * <pre>
 * tesseraql:
 *   i18n:
 *     defaultLocale: en              # the app's authoring locale
 *     locales: [en, ja]              # served locales; defaults to the catalogs found
 *     preference: principal.claim.locale   # user-preference source(s), highest priority
 * </pre>
 *
 * <p>The message catalog layers the app's {@code messages/<locale>.yml} files over the
 * framework's built-in {@code tql.*} texts, so apps can override any framework message.
 *
 * @param defaultTag the app default locale (normalized BCP-47 tag)
 * @param supportedTags the locales requests may negotiate, default first
 * @param preferenceSources user-preference source expressions tried in order
 *        ({@code principal.*} paths and {@code query.*} parameters)
 * @param catalog app catalog layered over the framework built-ins
 */
public record I18nSettings(String defaultTag, List<String> supportedTags,
        List<String> preferenceSources, MessageCatalog catalog) {

    /** Language tags of the framework's built-in {@code tesseraql/messages/<tag>.yml} catalogs. */
    private static final List<String> BUILTIN_TAGS = List.of("en", "ja");

    /** English-only settings over the framework built-ins (for tests and bare processors). */
    public static I18nSettings defaults() {
        return new I18nSettings("en", List.of("en"),
                List.of("preference.ui.locale", "principal.claim.locale"), builtinCatalog());
    }

    /** Reads i18n settings from config and the app home's {@code messages/} directory. */
    public static I18nSettings from(AppConfig config, Path appHome) {
        MessageCatalog appCatalog = MessageCatalog.load(appHome.resolve("messages"));
        MessageCatalog catalog = appCatalog.withFallback(builtinCatalog());

        String defaultTag = normalize(config.getString("tesseraql.i18n.defaultLocale")
                .orElse("en"));
        Set<String> supported = new LinkedHashSet<>();
        supported.add(defaultTag);
        Object declared = config.navigate("tesseraql.i18n.locales");
        if (declared instanceof List<?> tags) {
            tags.forEach(tag -> supported.add(normalize(String.valueOf(tag))));
        } else if (declared != null) {
            supported.add(normalize(String.valueOf(declared)));
        } else {
            supported.addAll(appCatalog.tags());
        }

        List<String> preferences = new ArrayList<>();
        Object preference = config.navigate("tesseraql.i18n.preference");
        if (preference instanceof List<?> sources) {
            sources.forEach(source -> preferences.add(String.valueOf(source)));
        } else if (preference != null) {
            preferences.add(String.valueOf(preference));
        } else {
            // Default order (roadmap Phase 48): the stored account preference (the full
            // preference key after the `preference.` prefix) wins over an IdP claim, so the
            // language a user picks in the account surface takes effect with zero
            // configuration. Operators reorder by declaring the list explicitly.
            preferences.add("preference.ui.locale");
            preferences.add("principal.claim.locale");
        }

        return new I18nSettings(defaultTag, List.copyOf(supported),
                List.copyOf(preferences), catalog);
    }

    /** The framework's built-in messages ({@code tql.input.*}, {@code tql.http.*}, ...). */
    public static MessageCatalog builtinCatalog() {
        MessageCatalog builtin = MessageCatalog.empty();
        for (String tag : BUILTIN_TAGS) {
            String resource = "tesseraql/messages/" + tag + ".yml";
            try (InputStream in = I18nSettings.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (in != null) {
                    builtin = MessageCatalog.parse(tag, in, "classpath:" + resource)
                            .withFallback(builtin);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return builtin;
    }

    /** Resolves a message for the locale, falling back to the default locale, then the key. */
    public String message(String tag, String key) {
        String resolved = catalog.resolve(tag, defaultTag, key);
        return resolved == null ? key : resolved;
    }

    private static String normalize(String tag) {
        return Locale.forLanguageTag(tag.trim()).toLanguageTag();
    }
}
