package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlHighlighterTest {

    @Test
    void wrapsKeywordsNumbersAndLineComments() {
        String html = SqlHighlighter.highlight("SELECT id, 1 -- note");

        assertThat(html).contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">SELECT</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"number\">1</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"comment\">-- note</span>");
        // Identifiers and operators stay plain (no token span around `id` or `,`).
        assertThat(html).contains(">SELECT</span> id");
    }

    @Test
    void wrapsTwoWayDirectivesAndBindsAsMeta() {
        String html = SqlHighlighter.highlight("WHERE id = /*%if a */ 1 /*%end*/");

        assertThat(html).contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">WHERE</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"meta\">/*%if a */</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"meta\">/*%end*/</span>");
    }

    @Test
    void treatsSingleQuotedLiteralsAsOneStringWithDoubledEscape() {
        String html = SqlHighlighter.highlight("name = 'it''s ok'");

        assertThat(html)
                .contains("<span class=\"hc-code__tok\" data-tok=\"string\">'it''s ok'</span>");
    }

    @Test
    void escapesHtmlInBothPlainTextAndTokens() {
        assertThat(SqlHighlighter.highlight("a < b")).contains("a &lt; b").doesNotContain("a < b");
        assertThat(SqlHighlighter.highlight("x = '<v>'"))
                .contains("data-tok=\"string\">'&lt;v&gt;'</span>");
    }

    @Test
    void carriesBlockCommentStateAcrossLines() {
        List<String> lines = SqlHighlighter
                .highlightLines("/* line one\nstill comment */ SELECT 1");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(
                "<span class=\"hc-code__tok\" data-tok=\"meta\">/* line one</span>");
        assertThat(lines.get(1)).contains("data-tok=\"meta\">still comment */</span>")
                .contains("data-tok=\"keyword\">SELECT</span>");
    }

    @Test
    void isLenientOnMalformedDrafts() {
        // An unterminated block comment or stray quote must never throw — it just colours to EOL.
        assertThatCode(() -> SqlHighlighter.highlight("/* unterminated"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SqlHighlighter.highlight("SELECT 'open")).doesNotThrowAnyException();
        assertThat(SqlHighlighter.highlight("/* unterminated"))
                .isEqualTo("<span class=\"hc-code__tok\" data-tok=\"meta\">/* unterminated</span>");
    }
}
