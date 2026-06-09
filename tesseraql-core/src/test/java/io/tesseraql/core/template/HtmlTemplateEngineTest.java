package io.tesseraql.core.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HtmlTemplateEngineTest {

    @Test
    void escapesVariablesByDefault() {
        String html = HtmlTemplateEngine.compile("<p>{{ name }}</p>")
                .render(Map.of("name", "<b>&\"x\""));
        assertThat(html).isEqualTo("<p>&lt;b&gt;&amp;&quot;x&quot;</p>");
    }

    @Test
    void rawVariableIsNotEscaped() {
        String html = HtmlTemplateEngine.compile("{{{ html }}}").render(Map.of("html", "<i>ok</i>"));
        assertThat(html).isEqualTo("<i>ok</i>");
    }

    @Test
    void iteratesCollectionSection() {
        String template = "<ul>{{# users }}<li>{{ name }}</li>{{/ users }}</ul>";
        String html = HtmlTemplateEngine.compile(template).render(Map.of(
                "users", List.of(Map.of("name", "sato"), Map.of("name", "suzuki"))));
        assertThat(html).isEqualTo("<ul><li>sato</li><li>suzuki</li></ul>");
    }

    @Test
    void invertedSectionRendersWhenEmpty() {
        String template = "{{# users }}x{{/ users }}{{^ users }}none{{/ users }}";
        assertThat(HtmlTemplateEngine.compile(template).render(Map.of("users", List.of())))
                .isEqualTo("none");
    }

    @Test
    void outerScopeVisibleInsideSection() {
        String template = "{{# rows }}{{ title }}:{{ id }} {{/ rows }}";
        String html = HtmlTemplateEngine.compile(template).render(Map.of(
                "title", "T",
                "rows", List.of(Map.of("id", 1), Map.of("id", 2))));
        assertThat(html).isEqualTo("T:1 T:2 ");
    }

    @Test
    void missingVariableRendersEmpty() {
        assertThat(HtmlTemplateEngine.compile("[{{ nope }}]").render(Map.of())).isEqualTo("[]");
    }
}
