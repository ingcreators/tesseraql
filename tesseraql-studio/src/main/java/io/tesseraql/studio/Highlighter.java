package io.tesseraql.studio;

import java.util.Locale;

/**
 * Dispatches a source file to the syntax tokenizer for its language (by extension), returning the
 * Hypermedia Components {@code hc-code} token markup (hc &ge; 0.1.4) for a server-rendered,
 * {@code th:utext} surface. 2-way SQL, route YAML, and Thymeleaf templates are highlighted; any
 * other file (or {@code null}) renders as HTML-escaped plain text.
 */
public final class Highlighter {

    private Highlighter() {
    }

    /** Token-annotated, HTML-escaped markup for {@code text}, chosen by {@code path}'s extension. */
    public static String highlight(String path, String text) {
        if (text == null) {
            return "";
        }
        return switch (extension(path)) {
            case "sql" -> SqlHighlighter.highlight(text);
            case "yml", "yaml" -> YamlHighlighter.highlight(text);
            case "html", "tpl" -> TemplateHighlighter.highlight(text);
            default -> SyntaxSpans.escapePlain(text);
        };
    }

    private static String extension(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
