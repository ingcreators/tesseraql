package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatesMessageTest {

    @TempDir
    Path home;

    @BeforeEach
    void appWithCatalogs() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), """
                users.list.title: Users
                """);
        Files.writeString(messages.resolve("ja.yml"), """
                users.list.title: ユーザー一覧
                """);
        Files.writeString(home.resolve("page.html"),
                "<h1 th:text=\"#{users.list.title}\">t</h1>"
                        + "<p lang=\"en\" th:lang=\"${#locale.toLanguageTag()}\">x</p>");
    }

    @Test
    void messageExpressionsResolvePerLocale() {
        assertThat(Templates.render(home, "page.html", Map.of(), Locale.forLanguageTag("ja")))
                .contains("<h1>ユーザー一覧</h1>")
                .contains("lang=\"ja\"");
        assertThat(Templates.render(home, "page.html", Map.of(), Locale.forLanguageTag("en")))
                .contains("<h1>Users</h1>");
    }

    @Test
    void regionLocaleFallsBackToBareLanguageCatalog() {
        assertThat(Templates.render(home, "page.html", Map.of(), Locale.forLanguageTag("ja-JP")))
                .contains("<h1>ユーザー一覧</h1>")
                .contains("lang=\"ja-JP\"");
    }

    @Test
    void localeLessRenderReadsEnglish() {
        assertThat(Templates.render(home, "page.html", Map.of()))
                .contains("<h1>Users</h1>");
    }

    @Test
    void missingKeysStayVisibleAsMarkers() throws Exception {
        Files.writeString(home.resolve("missing.html"),
                "<p th:text=\"#{absent.key}\">x</p>");
        assertThat(Templates.render(home, "missing.html", Map.of(), Locale.forLanguageTag("ja")))
                .contains("??absent.key_ja??");
    }
}
