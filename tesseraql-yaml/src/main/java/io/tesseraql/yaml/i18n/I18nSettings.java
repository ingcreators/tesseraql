package io.tesseraql.yaml.i18n;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *     locales: [en, ja]              # served locales; defaults to every catalog found
 *     preference: principal.claim.locale   # user-preference source(s), highest priority
 * </pre>
 *
 * <p>The message catalog layers the app's {@code messages/<locale>.yml} files over the
 * framework's built-in {@code tql.*} texts, so apps can override any framework message. With
 * no {@code locales:} declared the served set is every catalog the app can answer in — its own
 * files and the framework's built-in {@code en} and {@code ja} — so a browser asking for
 * Japanese gets the framework's Japanese chrome, error texts and input messages from an app
 * that never wrote a catalog. A declared {@code defaultLocale:} or {@code locales:} entry is
 * judged by the one locale rule ({@link
 * io.tesseraql.yaml.app.ExportDeclarations#isFormattableLocale}) at lint and here, at boot:
 * {@code ja_JP} is not a language tag ({@code Locale.forLanguageTag} folds it to {@code und},
 * and every catalog lookup for {@code und} throws — an app whose every error response was a
 * 500), and {@code japanese} parses but renders as the root locale.
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

    /**
     * TQL-YAML-1065: a {@code tesseraql.i18n.defaultLocale} or {@code locales} entry names a
     * locale the runtime cannot serve — not a BCP-47 language tag ({@code ja_JP}), or one the
     * JDK has no formatting data for. Judged by the linter as a finding and here as the boot
     * refusal, so the misspelling is named at the key rather than met as a 500 on the first
     * error response.
     */
    public static final TqlErrorCode UNSERVABLE_LOCALE = new TqlErrorCode(TqlDomain.YAML, 1065);

    /** English-only settings over the framework built-ins (for tests and bare processors). */
    public static I18nSettings defaults() {
        return new I18nSettings("en", List.of("en"),
                List.of("preference.ui.locale", "principal.claim.locale"), builtinCatalog());
    }

    /**
     * Reads i18n settings from config and the app home's {@code messages/} directory; refuses
     * the first {@link #declarationProblems(AppConfig) declaration problem} with
     * {@link #UNSERVABLE_LOCALE}.
     */
    public static I18nSettings from(AppConfig config, Path appHome) {
        declarationProblems(config).entrySet().stream().findFirst().ifPresent(problem -> {
            throw new TqlException(UNSERVABLE_LOCALE,
                    problem.getKey() + ": " + problem.getValue());
        });
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
            // Every layer, not the app's files alone: the built-in catalogs answer in
            // Japanese for an app that never wrote a messages/ directory, and a set that
            // omitted them negotiated `Accept-Language: ja` to English by default.
            supported.addAll(catalog.tags());
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

    /**
     * The declared locales the runtime cannot serve, keyed by the configuration key that names
     * each ({@code tesseraql.i18n.locales[1]} for a list entry) with the reason as the value;
     * empty when every declaration holds. The linter reports each; {@link #from} refuses the
     * first. A blank entry is a problem too: it would serve as the root locale.
     */
    public static Map<String, String> declarationProblems(AppConfig config) {
        Map<String, String> problems = new LinkedHashMap<>();
        // Literal keys at every read, so the generated configuration reference sees them.
        config.getString("tesseraql.i18n.defaultLocale").ifPresent(value -> problem(value)
                .ifPresent(reason -> problems.put("tesseraql.i18n.defaultLocale", reason)));
        Object declared = config.navigate("tesseraql.i18n.locales");
        if (declared instanceof List<?> tags) {
            for (int i = 0; i < tags.size(); i++) {
                String key = "tesseraql.i18n.locales[" + i + "]";
                problem(String.valueOf(tags.get(i)))
                        .ifPresent(reason -> problems.put(key, reason));
            }
        } else if (declared != null) {
            problem(String.valueOf(declared))
                    .ifPresent(reason -> problems.put("tesseraql.i18n.locales", reason));
        }
        return problems;
    }

    private static java.util.Optional<String> problem(String value) {
        return value.isBlank()
                ? java.util.Optional.of("a locale is blank (expected e.g. en, ja-JP)")
                : ExportDeclarations.localeProblem(value);
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
