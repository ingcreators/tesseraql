package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(settings.preferenceSources()).containsExactly("principal.claim.locale");
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
