package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

class YamlHighlighterTest {

    @Test
    void highlightsMappingKeysStringsNumbersAndComments() {
        String html = YamlHighlighter.highlight("name: 'sato'  # a person\nage: 42");

        assertThat(html).contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">name</span>:")
                .contains("<span class=\"hc-code__tok\" data-tok=\"string\">'sato'</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"comment\"># a person</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">age</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"number\">42</span>");
    }

    @Test
    void highlightsBooleanScalarsAnchorsAndDocumentMarkers() {
        assertThat(YamlHighlighter.highlight("enabled: true"))
                .contains("data-tok=\"keyword\">true</span>");
        // the '&' of the anchor is HTML-escaped in the rendered token
        assertThat(YamlHighlighter.highlight("base: &anchor value"))
                .contains("data-tok=\"meta\">&amp;anchor</span>");
        assertThat(YamlHighlighter.highlight("---")).contains("data-tok=\"meta\">---</span>");
    }

    @Test
    void keepsListMarkersAndIndentPlainAndEscapesHtml() {
        String html = YamlHighlighter.highlight("  - id: '<x>'");
        assertThat(html).startsWith("  - ") // indent + list marker stay plain
                .contains("data-tok=\"keyword\">id</span>")
                .contains("data-tok=\"string\">'&lt;x&gt;'</span>");
    }

    @Test
    void isLenientAndPreservesLineCount() {
        List<String> lines = YamlHighlighter.highlightLines("a: 1\n: broken\n# just a comment");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(2)).isEqualTo(
                "<span class=\"hc-code__tok\" data-tok=\"comment\"># just a comment</span>");
        assertThatCode(() -> YamlHighlighter.highlight(":::\n\"unterminated"))
                .doesNotThrowAnyException();
    }
}
