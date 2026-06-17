package io.tesseraql.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A lenient, dependency-free YAML syntax tokenizer emitting the Hypermedia Components
 * {@code hc-code} token contract. Mapping keys and the {@code true/false/null/…} scalars are
 * {@code keyword}; quoted values are {@code string}; numbers are {@code number}; {@code #} remarks
 * are {@code comment}; anchors/aliases/tags ({@code &a}/{@code *a}/{@code !t}) and the document
 * markers ({@code ---}/{@code ...}) are {@code meta}. Everything else stays plain. It never throws
 * and HTML-escapes all text; the output is safe for {@code th:utext}.
 */
public final class YamlHighlighter {

    private static final Set<String> WORDS = Set.of(
            "true", "false", "null", "yes", "no", "on", "off");

    private YamlHighlighter() {
    }

    /** Renders the whole document as token-annotated, HTML-escaped markup (lines joined by LF). */
    public static String highlight(String yaml) {
        return String.join("\n", highlightLines(yaml));
    }

    /** Renders each line as token-annotated, HTML-escaped markup (YAML has no cross-line tokens). */
    public static List<String> highlightLines(String yaml) {
        String[] lines = yaml.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            StringBuilder html = new StringBuilder();
            highlightLine(line, html);
            out.add(html.toString());
        }
        return out;
    }

    private static void highlightLine(String line, StringBuilder out) {
        int len = line.length();
        int i = 0;
        while (i < len && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        SyntaxSpans.escape(out, line.substring(0, i)); // leading indent
        if (line.startsWith("---", i) || line.startsWith("...", i)) {
            SyntaxSpans.span(out, "meta", line.substring(i, i + 3));
            scanValue(line, i + 3, out);
            return;
        }
        while (line.startsWith("- ", i)) {
            SyntaxSpans.escape(out, "- ");
            i += 2;
        }
        int colon = keyColon(line, i);
        if (colon >= 0) {
            String key = line.substring(i, colon);
            char first = key.isEmpty() ? '\0' : key.charAt(0);
            SyntaxSpans.span(out, first == '"' || first == '\'' ? "string" : "keyword", key);
            SyntaxSpans.escape(out, ":");
            scanValue(line, colon + 1, out);
            return;
        }
        scanValue(line, i, out);
    }

    /** Index of the {@code :} ending a mapping key (followed by a space or EOL), skipping quotes. */
    private static int keyColon(String line, int from) {
        int len = line.length();
        char quote = 0;
        for (int i = from; i < len; i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return -1; // a remark before any colon — this is not a mapping line
            } else if (c == ':' && (i + 1 >= len || line.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** Tokenizes a value/scalar region: comments, quoted strings, numbers, bool/null, anchors. */
    private static void scanValue(String line, int from, StringBuilder out) {
        int len = line.length();
        int pos = from;
        char prev = pos > 0 ? line.charAt(pos - 1) : ' ';
        StringBuilder plain = new StringBuilder();
        while (pos < len) {
            char c = line.charAt(pos);
            boolean boundary = prev == ' ' || prev == '\t' || prev == ':' || pos == from;
            if (c == '#' && (prev == ' ' || prev == '\t')) {
                flush(out, plain);
                SyntaxSpans.span(out, "comment", line.substring(pos));
                return;
            }
            if (c == '"' || c == '\'') {
                flush(out, plain);
                int end = pos + 1;
                while (end < len && line.charAt(end) != c) {
                    end++;
                }
                if (end < len) {
                    end++;
                }
                SyntaxSpans.span(out, "string", line.substring(pos, end));
                prev = line.charAt(end - 1);
                pos = end;
                continue;
            }
            if ((c == '&' || c == '*' || c == '!') && boundary) {
                flush(out, plain);
                int end = pos + 1;
                while (end < len && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                SyntaxSpans.span(out, "meta", line.substring(pos, end));
                prev = line.charAt(end - 1);
                pos = end;
                continue;
            }
            if (Character.isDigit(c) && boundary) {
                int end = pos;
                while (end < len && (Character.isLetterOrDigit(line.charAt(end))
                        || ".-+".indexOf(line.charAt(end)) >= 0)) {
                    end++;
                }
                flush(out, plain);
                SyntaxSpans.span(out, "number", line.substring(pos, end));
                prev = line.charAt(end - 1);
                pos = end;
                continue;
            }
            if (Character.isLetter(c) && boundary) {
                int end = pos;
                while (end < len && (Character.isLetterOrDigit(line.charAt(end))
                        || line.charAt(end) == '_')) {
                    end++;
                }
                String word = line.substring(pos, end);
                if (WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                    flush(out, plain);
                    SyntaxSpans.span(out, "keyword", word);
                } else {
                    plain.append(word);
                }
                prev = word.charAt(word.length() - 1);
                pos = end;
                continue;
            }
            plain.append(c);
            prev = c;
            pos++;
        }
        flush(out, plain);
    }

    private static void flush(StringBuilder out, StringBuilder plain) {
        if (plain.length() > 0) {
            SyntaxSpans.escape(out, plain.toString());
            plain.setLength(0);
        }
    }
}
