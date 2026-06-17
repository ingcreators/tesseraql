package io.tesseraql.studio;

/**
 * Shared building blocks for the server-side syntax highlighters: HTML-escaping and the Hypermedia
 * Components {@code hc-code} token span (hc &ge; 0.1.4). A classified lexeme becomes
 * {@code <span class="hc-code__tok" data-tok="…">escaped</span>}; plain runs are escaped directly.
 * The {@code data-tok} value is always a literal type name, so the only unescaped markup is the
 * fixed wrapper — the rendered output is safe to emit with {@code th:utext}.
 */
final class SyntaxSpans {

    private SyntaxSpans() {
    }

    /** Appends a classified token as an {@code hc-code__tok} span with its text HTML-escaped. */
    static void span(StringBuilder out, String tok, String text) {
        out.append("<span class=\"hc-code__tok\" data-tok=\"").append(tok).append("\">");
        escape(out, text);
        out.append("</span>");
    }

    /** Appends {@code text} with the HTML metacharacters {@code & < >} escaped. */
    static void escape(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }

    /** The whole string as escaped plain text (no token spans) — for unknown/unsupported files. */
    static String escapePlain(String text) {
        StringBuilder out = new StringBuilder(text.length());
        escape(out, text);
        return out.toString();
    }
}
