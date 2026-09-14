package io.tesseraql.yaml.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class I18nSettingsTest {

    @TempDir
    Path home;

    @Test
    void defaultsToEnglishWithDiscoveredCatalogLocales() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("ja.yml"), "greeting: こんにちは\n");

        I18nSettings settings = I18nSettings.from(new AppConfig(Map.of()), home);

        assertThat(settings.defaultTag()).isEqualTo("en");
        assertThat(settings.supportedTags()).containsExactly("en", "ja");
        assertThat(settings.preferenceSources())
                .containsExactly("preference.ui.locale", "principal.claim.locale");
        assertThat(settings.catalog().resolve("ja", "greeting")).isEqualTo("こんにちは");
    }

    @Test
    void configDeclarationsOverrideDiscovery() {
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of("i18n", Map.of(
                "defaultLocale", "ja",
                "locales", List.of("ja", "en-US"),
                "preference", List.of("query.lang", "principal.claim.locale")))));

        I18nSettings settings = I18nSettings.from(config, home);

        assertThat(settings.defaultTag()).isEqualTo("ja");
        assertThat(settings.supportedTags()).containsExactly("ja", "en-US");
        assertThat(settings.preferenceSources())
                .containsExactly("query.lang", "principal.claim.locale");
    }

    /**
     * {@code ja_JP} is the spelling Java programmers reach for first; {@code Locale.forLanguageTag}
     * folds it to {@code und}, and every catalog lookup for {@code und} throws — so a runtime
     * that accepted it answered 500 to its first error response. Refused at the key instead.
     */
    @Test
    void aDefaultLocaleTheJdkCannotFormatIsRefused() {
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of("i18n", Map.of(
                "defaultLocale", "ja_JP"))));

        assertThatThrownBy(() -> I18nSettings.from(config, home))
                .isInstanceOf(TqlException.class)
                .hasMessageStartingWith("TQL-YAML-1065: tesseraql.i18n.defaultLocale: 'ja_JP'")
                .hasMessageContaining("not a language tag the JDK can format");
    }

    @Test
    void everyDeclaredLocaleIsJudgedAndNamedByItsKey() {
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of("i18n", Map.of(
                "defaultLocale", "en",
                "locales", List.of("en", "ja-JP-u-ca-japanese", "ja_JP", "und", " ")))));

        assertThat(I18nSettings.declarationProblems(config))
                .containsOnlyKeys("tesseraql.i18n.locales[2]", "tesseraql.i18n.locales[3]",
                        "tesseraql.i18n.locales[4]");
        assertThatThrownBy(() -> I18nSettings.from(config, home))
                .isInstanceOf(TqlException.class)
                .hasMessageStartingWith("TQL-YAML-1065: tesseraql.i18n.locales[2]: 'ja_JP'");
        assertThat(I18nSettings.declarationProblems(new AppConfig(Map.of("tesseraql",
                Map.of("i18n", Map.of("defaultLocale", "ja-JP", "locales", "ja"))))))
                .isEmpty();
    }

    @Test
    void messageFallsBackToDefaultLocaleThenKey() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), "only.english: English text\n");
        Files.writeString(messages.resolve("ja.yml"), "greeting: こんにちは\n");

        I18nSettings settings = I18nSettings.from(new AppConfig(Map.of()), home);

        assertThat(settings.message("ja", "only.english")).isEqualTo("English text");
        assertThat(settings.message("ja", "missing.key")).isEqualTo("missing.key");
    }
}
