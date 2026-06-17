package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HighlighterTest {

    @Test
    void dispatchesByFileExtension() {
        assertThat(Highlighter.highlight("web/api/users/search.sql", "SELECT 1"))
                .contains("data-tok=\"keyword\">SELECT</span>");
        assertThat(Highlighter.highlight("config/tesseraql.yml", "name: 'x'"))
                .contains("data-tok=\"keyword\">name</span>");
        assertThat(Highlighter.highlight("web/users/index.html", "<p>hi</p>"))
                .contains("data-tok=\"keyword\">p</span>");
    }

    @Test
    void rendersUnknownExtensionsAndNullAsPlainEscapedText() {
        // No tokenizer for .txt: escaped plain, no token spans.
        assertThat(Highlighter.highlight("notes.txt", "SELECT <a> & 1"))
                .isEqualTo("SELECT &lt;a&gt; &amp; 1")
                .doesNotContain("hc-code__tok");
        assertThat(Highlighter.highlight("noext", "x")).isEqualTo("x");
        assertThat(Highlighter.highlight(null, "x")).isEqualTo("x");
        assertThat(Highlighter.highlight("a.sql", null)).isEmpty();
    }
}
