package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateHighlighterTest {

    @Test
    void highlightsTagsThymeleafAttributesAndStringValues() {
        String html = TemplateHighlighter
                .highlight("<p th:text=\"${user.name}\" class=\"x\">hi</p>");

        assertThat(html).contains("<span class=\"hc-code__tok\" data-tok=\"keyword\">p</span>")
                .contains("<span class=\"hc-code__tok\" data-tok=\"meta\">th:text</span>")
                .contains(
                        "<span class=\"hc-code__tok\" data-tok=\"string\">\"${user.name}\"</span>")
                // text between tags stays plain and HTML-escaped
                .contains("&gt;hi&lt;");
    }

    @Test
    void highlightsThymeleafInlineExpressions() {
        assertThat(TemplateHighlighter.highlight("<span>[[${count}]]</span>"))
                .contains("<span class=\"hc-code__tok\" data-tok=\"meta\">[[${count}]]</span>");
    }

    @Test
    void carriesHtmlCommentStateAcrossLines() {
        List<String> lines = TemplateHighlighter.highlightLines("<!-- start\nstill --> <b>x</b>");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .isEqualTo(
                        "<span class=\"hc-code__tok\" data-tok=\"comment\">&lt;!-- start</span>");
        assertThat(lines.get(1)).contains("data-tok=\"comment\">still --&gt;</span>")
                .contains("data-tok=\"keyword\">b</span>");
    }

    @Test
    void escapesMarkupAndIsLenient() {
        // The literal angle brackets of the rendered tags must be escaped in the output.
        assertThat(TemplateHighlighter.highlight("<div>")).contains("&lt;").doesNotContain("<div>");
        assertThatCode(() -> TemplateHighlighter.highlight("<unterminated th:if=\"$"))
                .doesNotThrowAnyException();
    }
}
