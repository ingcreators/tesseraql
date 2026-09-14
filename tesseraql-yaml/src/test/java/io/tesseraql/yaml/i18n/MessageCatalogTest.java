package io.tesseraql.yaml.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageCatalogTest {

    @TempDir
    Path home;

    @Test
    void loadsLocaleFilesAndFlattensNestedKeys() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), """
                users:
                  provision:
                    unknown-user: The user does not exist.
                  count: 3
                """);
        Files.writeString(messages.resolve("ja.yml"), """
                users.provision.unknown-user: 指定されたユーザーは存在しません。
                """);

        MessageCatalog catalog = MessageCatalog.load(messages);

        assertThat(catalog.tags()).containsExactly("en", "ja");
        assertThat(catalog.resolve("en", "users.provision.unknown-user"))
                .isEqualTo("The user does not exist.");
        assertThat(catalog.resolve("ja", "users.provision.unknown-user"))
                .isEqualTo("指定されたユーザーは存在しません。");
        assertThat(catalog.resolve("en", "users.count")).isEqualTo("3");
    }

    @Test
    void missingDirectoryIsEmpty() {
        MessageCatalog catalog = MessageCatalog.load(home.resolve("messages"));
        assertThat(catalog.tags()).isEmpty();
        assertThat(catalog.resolve("en", "any.key")).isNull();
    }

    @Test
    void regionTagFallsBackToBareLanguage() {
        MessageCatalog catalog = MessageCatalog.parse("ja",
                yaml("greeting: こんにちは"), "ja.yml");
        assertThat(catalog.resolve("ja-JP", "greeting")).isEqualTo("こんにちは");
    }

    @Test
    void exactRegionTagWinsOverBareLanguage() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), "color: colour-neutral\n");
        Files.writeString(messages.resolve("en-US.yml"), "color: color\n");

        MessageCatalog catalog = MessageCatalog.load(messages);

        assertThat(catalog.resolve("en-US", "color")).isEqualTo("color");
        assertThat(catalog.resolve("en-GB", "color")).isEqualTo("colour-neutral");
    }

    @Test
    void defaultLocaleFillsMissingTranslations() {
        MessageCatalog catalog = MessageCatalog.parse("en",
                yaml("only-english: English text"), "en.yml");
        assertThat(catalog.resolve("ja", "en", "only-english")).isEqualTo("English text");
        assertThat(catalog.resolve("ja", "en", "absent")).isNull();
    }

    @Test
    void appLayerWinsOverFallbackLayer() {
        MessageCatalog framework = MessageCatalog.parse("en",
                yaml("tql.input.required: This field is required.\ntql.http.404: Not Found"),
                "framework");
        MessageCatalog app = MessageCatalog.parse("en",
                yaml("tql.input.required: Please fill this in."), "app")
                .withFallback(framework);

        assertThat(app.resolve("en", "tql.input.required")).isEqualTo("Please fill this in.");
        assertThat(app.resolve("en", "tql.http.404")).isEqualTo("Not Found");
        assertThat(app.tags()).contains("en");
    }

    @Test
    void forLocaleMergesLanguageExactAndFallbackLayers() {
        MessageCatalog framework = MessageCatalog.parse("ja",
                yaml("confirm.cancel: キャンセル\nshared: framework-ja"), "framework");
        MessageCatalog app = MessageCatalog.parse("ja-JP", yaml("shared: app-ja-JP"), "app")
                .withFallback(framework);

        Map<String, String> visible = app.forLocale("ja-JP");
        assertThat(visible).containsEntry("confirm.cancel", "キャンセル")
                .containsEntry("shared", "app-ja-JP");
    }

    @Test
    void interpolatesNamedPlaceholders() {
        assertThat(MessageCatalog.interpolate("Must be at least {min}.", Map.of("min", 3)))
                .isEqualTo("Must be at least 3.");
        assertThat(MessageCatalog.interpolate("{a} and {missing}", Map.of("a", "x")))
                .isEqualTo("x and {missing}");
        assertThat(MessageCatalog.interpolate("no placeholders", Map.of("a", "x")))
                .isEqualTo("no placeholders");
        // The identifier contract (docs/unicode-identifiers.md): a Japanese placeholder
        // interpolates instead of reaching the user as raw braces.
        assertThat(MessageCatalog.interpolate("{顧客名} は必須です。", Map.of("顧客名", "山田")))
                .isEqualTo("山田 は必須です。");
    }

    @Test
    void rejectsInvalidLocaleFilename() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("not_a_tag.yml"), "k: v\n");

        assertThatThrownBy(() -> MessageCatalog.load(messages))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("not a BCP-47 language tag")
                .extracting(ex -> ((TqlException) ex).code().toString())
                .isEqualTo("TQL-YAML-1007");
    }

    @Test
    void rejectsListValues() {
        assertThatThrownBy(() -> MessageCatalog.parse("en",
                yaml("bad:\n  - one\n  - two"), "en.yml"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("holds a list");
    }

    @Test
    void rejectsNonMapDocument() {
        assertThatThrownBy(() -> MessageCatalog.parse("en", yaml("- just\n- a list"), "en.yml"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("must be a map");
    }

    /**
     * A render reads the catalog once (docs/audit-medium-leads.md slice 6, F118): twenty
     * {@code #{key}} lookups on one page cost one directory listing, not twenty — and a catalog
     * edit still lands on the very next render. Every {@code #{}} used to call {@code live()},
     * which lists {@code messages/} and stats each file, so a list page paid twenty listings and
     * forty stats per render, tens of milliseconds on a container's overlay volume.
     */
    @Test
    void aRenderListsTheCatalogDirectoryOnce() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        StringBuilder catalog = new StringBuilder();
        StringBuilder page = new StringBuilder(
                "<html xmlns:th=\"http://www.thymeleaf.org\"><body>");
        for (int i = 1; i <= 20; i++) {
            catalog.append("k").append(i).append(": value-").append(i).append('\n');
            page.append("<span th:text=\"#{k").append(i).append("}\">x</span>");
        }
        page.append("</body></html>");
        Files.writeString(messages.resolve("en.yml"), catalog.toString());
        Files.writeString(home.resolve("page.html"), page.toString());

        long before = MessageCatalog.directoryListings();
        String html = io.tesseraql.yaml.template.Templates.render(home, "page.html", Map.of());
        assertThat(html).contains("value-1</span>").contains("value-20</span>");
        assertThat(MessageCatalog.directoryListings() - before)
                .as("directory listings for one render of twenty keys").isEqualTo(1);

        // Liveness kept: the edit is on the next render, at the same cost.
        Files.writeString(messages.resolve("en.yml"),
                catalog.toString().replace("k1: value-1\n", "k1: edited-1\n"));
        before = MessageCatalog.directoryListings();
        html = io.tesseraql.yaml.template.Templates.render(home, "page.html", Map.of());
        assertThat(html).contains("edited-1</span>").doesNotContain("value-1</span>");
        assertThat(MessageCatalog.directoryListings() - before).isEqualTo(1);
    }

    private static InputStream yaml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
