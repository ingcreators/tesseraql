package io.tesseraql.core.template;

import java.util.List;

/** Parsed node of an {@link HtmlTemplateEngine} template. */
sealed interface TemplateNode {

    /** Literal markup emitted verbatim. */
    record Text(String value) implements TemplateNode {
    }

    /** A {@code {{ path }}} (escaped) or {@code {{{ path }}}} (raw) substitution. */
    record Variable(String path, boolean escaped) implements TemplateNode {
    }

    /** A {@code {{# key }}} section or {@code {{^ key }}} inverted section. */
    record Section(String key, boolean inverted, List<TemplateNode> body) implements TemplateNode {
        public Section {
            body = List.copyOf(body);
        }
    }
}
