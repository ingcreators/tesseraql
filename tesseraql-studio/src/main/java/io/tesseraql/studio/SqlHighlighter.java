package io.tesseraql.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A lenient, dependency-free syntax tokenizer for TesseraQL 2-way SQL that emits the Hypermedia
 * Components {@code hc-code} token contract (hc &ge; 0.1.4): classified lexemes are wrapped in
 * {@code <span class="hc-code__tok" data-tok="…">} and everything else stays plain (HTML-escaped)
 * text, so the kit colours it and unknown spans degrade to plain code.
 *
 * <p>It never throws — a half-typed or malformed draft simply yields more plain text — and threads
 * block-comment state across lines so the per-line coverage gutter highlights correctly. Token
 * types: {@code keyword}, {@code string}, {@code number}, {@code comment} (a {@code --} line
 * remark), and {@code meta} (every {@code /* … *}{@code /} block comment — the 2-way SQL
 * directive/bind convention). Identifiers, operators, and whitespace are left as plain code.
 *
 * <p>The output is already HTML-escaped, so a template renders it with {@code th:utext}; the only
 * unescaped markup is the fixed token-span wrapper whose {@code data-tok} is a literal type name.
 */
public final class SqlHighlighter {

    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "between",
            "join", "inner", "left", "right", "full", "outer", "cross", "on", "using", "as",
            "group", "by", "order", "having", "limit", "offset", "fetch", "distinct", "union",
            "all", "exists", "case", "when", "then", "else", "end", "asc", "desc", "with",
            "insert", "into", "values", "update", "set", "delete", "returning", "count", "sum",
            "avg", "min", "max", "coalesce", "cast", "true", "false");

    private SqlHighlighter() {
    }

    /** Renders the whole statement as token-annotated, HTML-escaped markup (lines joined by LF). */
    public static String highlight(String sql) {
        return String.join("\n", highlightLines(sql));
    }

    /**
     * Renders each source line as token-annotated, HTML-escaped markup — one entry per line, the
     * same {@code split("\n", -1)} the coverage gutter uses — threading block-comment state across
     * lines.
     */
    public static List<String> highlightLines(String sql) {
        String[] lines = sql.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inBlockComment = false;
        for (String line : lines) {
            StringBuilder html = new StringBuilder();
            inBlockComment = highlightLine(line, inBlockComment, html);
            out.add(html.toString());
        }
        return out;
    }

    private static boolean highlightLine(String line, boolean inBlockComment, StringBuilder out) {
        int len = line.length();
        int pos = 0;
        if (inBlockComment) {
            int close = line.indexOf("*/");
            if (close < 0) {
                token(out, "meta", line);
                return true; // still inside the block comment on the next line
            }
            token(out, "meta", line.substring(0, close + 2));
            pos = close + 2;
        }
        StringBuilder plain = new StringBuilder();
        while (pos < len) {
            char c = line.charAt(pos);
            char next = pos + 1 < len ? line.charAt(pos + 1) : '\0';
            if (c == '-' && next == '-') {
                flush(out, plain);
                token(out, "comment", line.substring(pos));
                return false; // a -- comment runs to end of line
            }
            if (c == '/' && next == '*') {
                flush(out, plain);
                int close = line.indexOf("*/", pos + 2);
                if (close < 0) {
                    token(out, "meta", line.substring(pos));
                    return true; // block comment continues on the next line
                }
                token(out, "meta", line.substring(pos, close + 2));
                pos = close + 2;
                continue;
            }
            if (c == '\'') {
                flush(out, plain);
                int end = pos + 1;
                while (end < len) {
                    if (line.charAt(end) == '\'') {
                        if (end + 1 < len && line.charAt(end + 1) == '\'') {
                            end += 2; // a doubled '' escape stays inside the string
                            continue;
                        }
                        end++;
                        break;
                    }
                    end++;
                }
                token(out, "string", line.substring(pos, end));
                pos = end;
                continue;
            }
            if (Character.isDigit(c)) {
                flush(out, plain);
                int end = pos;
                while (end < len && (Character.isLetterOrDigit(line.charAt(end))
                        || line.charAt(end) == '.')) {
                    end++;
                }
                token(out, "number", line.substring(pos, end));
                pos = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = pos;
                while (end < len && (Character.isLetterOrDigit(line.charAt(end))
                        || line.charAt(end) == '_')) {
                    end++;
                }
                String word = line.substring(pos, end);
                if (KEYWORDS.contains(word.toLowerCase(Locale.ROOT))) {
                    flush(out, plain);
                    token(out, "keyword", word);
                } else {
                    plain.append(word); // identifiers stay plain
                }
                pos = end;
                continue;
            }
            plain.append(c); // operators, punctuation, whitespace
            pos++;
        }
        flush(out, plain);
        return false;
    }

    private static void flush(StringBuilder out, StringBuilder plain) {
        if (plain.length() > 0) {
            escape(out, plain.toString());
            plain.setLength(0);
        }
    }

    private static void token(StringBuilder out, String tok, String text) {
        out.append("<span class=\"hc-code__tok\" data-tok=\"").append(tok).append("\">");
        escape(out, text);
        out.append("</span>");
    }

    private static void escape(StringBuilder out, String text) {
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
}
