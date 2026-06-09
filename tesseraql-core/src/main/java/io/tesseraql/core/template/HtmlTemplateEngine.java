package io.tesseraql.core.template;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free HTML template engine for server-rendered fragments (design ch. 12).
 *
 * <p>It supports a Mustache subset that stays valid HTML so templates remain editable as plain
 * markup (Hypermedia Components / Light DOM):
 * <ul>
 *   <li>{@code {{ path }}} — value, HTML-escaped by default</li>
 *   <li>{@code {{{ path }}}} — raw value, not escaped (use sparingly)</li>
 *   <li>{@code {{# key }} ... {{/ key }}} — section: iterates a collection, or renders once when
 *       the value is truthy</li>
 *   <li>{@code {{^ key }} ... {{/ key }}} — inverted section: renders when the value is empty/false</li>
 * </ul>
 *
 * <p>Inside a section the current element becomes the innermost scope, with outer scopes still
 * visible. Paths are dotted (for example {@code user.name}).
 */
public final class HtmlTemplateEngine {

    private static final TqlErrorCode TEMPLATE_ERROR = new TqlErrorCode(TqlDomain.TPL, 1001);

    private final List<TemplateNode> nodes;

    private HtmlTemplateEngine(List<TemplateNode> nodes) {
        this.nodes = nodes;
    }

    /** Compiles a template source string. */
    public static HtmlTemplateEngine compile(String source) {
        return new HtmlTemplateEngine(TemplateParser.parse(source));
    }

    /** Renders the template against a model. */
    public String render(Map<String, Object> model) {
        StringBuilder out = new StringBuilder();
        Deque<Object> scopes = new ArrayDeque<>();
        scopes.push(model);
        render(nodes, scopes, out);
        return out.toString();
    }

    private void render(List<TemplateNode> nodes, Deque<Object> scopes, StringBuilder out) {
        for (TemplateNode node : nodes) {
            switch (node) {
                case TemplateNode.Text text -> out.append(text.value());
                case TemplateNode.Variable var -> {
                    Object value = resolve(var.path(), scopes);
                    if (value != null) {
                        out.append(var.escaped() ? escape(String.valueOf(value)) : String.valueOf(value));
                    }
                }
                case TemplateNode.Section section -> renderSection(section, scopes, out);
            }
        }
    }

    private void renderSection(TemplateNode.Section section, Deque<Object> scopes, StringBuilder out) {
        Object value = resolve(section.key(), scopes);
        if (section.inverted()) {
            if (isEmpty(value)) {
                render(section.body(), scopes, out);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                scopes.push(element);
                render(section.body(), scopes, out);
                scopes.pop();
            }
        } else if (!isEmpty(value)) {
            scopes.push(value);
            render(section.body(), scopes, out);
            scopes.pop();
        }
    }

    private static boolean isEmpty(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof CharSequence sequence) {
            return sequence.isEmpty();
        }
        return false;
    }

    /** Resolves a dotted path against the scope stack, innermost first. */
    private Object resolve(String path, Deque<Object> scopes) {
        String[] segments = path.split("\\.");
        for (Object scope : scopes) {
            Object value = resolveFrom(scope, segments);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object resolveFrom(Object scope, String[] segments) {
        Object current = scope;
        for (String segment : segments) {
            if (".".equals(segment)) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** HTML-escapes a string for safe inclusion in element/attribute text. */
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static TqlException error(String message) {
        return new TqlException(TEMPLATE_ERROR, message);
    }
}
