package io.tesseraql.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * A lenient, dependency-free HTML/Thymeleaf template tokenizer emitting the Hypermedia Components
 * {@code hc-code} token contract. Tag names are {@code keyword}; namespaced/{@code th:}/{@code hx-}/
 * {@code data-} attributes and Thymeleaf inline expressions ({@code [[…]]}/{@code [(…)]}) are
 * {@code meta}; quoted attribute values are {@code string}; {@code <!-- … -->} (which may span
 * lines) is {@code comment}; text and plain attributes stay plain. It never throws and HTML-escapes
 * all text; the output is safe for {@code th:utext}. The token set is coarse for HTML (no dedicated
 * tag/attribute types yet — see hc #264); still clearly better than plain.
 */
public final class TemplateHighlighter {

    private TemplateHighlighter() {
    }

    /** Renders the whole template as token-annotated, HTML-escaped markup (lines joined by LF). */
    public static String highlight(String html) {
        return String.join("\n", highlightLines(html));
    }

    /** Renders each line, threading {@code <!-- --> } comment state across lines. */
    public static List<String> highlightLines(String html) {
        String[] lines = html.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inComment = false;
        for (String line : lines) {
            StringBuilder b = new StringBuilder();
            inComment = highlightLine(line, inComment, b);
            out.add(b.toString());
        }
        return out;
    }

    private static boolean highlightLine(String line, boolean inComment, StringBuilder out) {
        int len = line.length();
        int pos = 0;
        if (inComment) {
            int close = line.indexOf("-->");
            if (close < 0) {
                SyntaxSpans.span(out, "comment", line);
                return true;
            }
            SyntaxSpans.span(out, "comment", line.substring(0, close + 3));
            pos = close + 3;
        }
        StringBuilder plain = new StringBuilder();
        while (pos < len) {
            char c = line.charAt(pos);
            if (line.startsWith("<!--", pos)) {
                flush(out, plain);
                int close = line.indexOf("-->", pos + 4);
                if (close < 0) {
                    SyntaxSpans.span(out, "comment", line.substring(pos));
                    return true;
                }
                SyntaxSpans.span(out, "comment", line.substring(pos, close + 3));
                pos = close + 3;
                continue;
            }
            if (line.startsWith("[[", pos) || line.startsWith("[(", pos)) {
                flush(out, plain);
                String closing = line.charAt(pos + 1) == '[' ? "]]" : ")]";
                int close = line.indexOf(closing, pos + 2);
                int end = close < 0 ? len : close + 2;
                SyntaxSpans.span(out, "meta", line.substring(pos, end));
                pos = end;
                continue;
            }
            if (c == '<' && pos + 1 < len
                    && (Character.isLetter(line.charAt(pos + 1)) || line.charAt(pos + 1) == '/')) {
                pos = tag(line, pos, out, plain);
                continue;
            }
            plain.append(c);
            pos++;
        }
        flush(out, plain);
        return false;
    }

    /** Scans a tag from {@code <} to the matching {@code >} (or EOL), emitting its tokens. */
    private static int tag(String line, int start, StringBuilder out, StringBuilder plain) {
        int len = line.length();
        int pos = start + 1;
        plain.append('<');
        if (pos < len && line.charAt(pos) == '/') {
            plain.append('/');
            pos++;
        }
        int nameStart = pos;
        while (pos < len && (Character.isLetterOrDigit(line.charAt(pos))
                || ":-_".indexOf(line.charAt(pos)) >= 0)) {
            pos++;
        }
        flush(out, plain); // the '<' or '</'
        if (pos > nameStart) {
            SyntaxSpans.span(out, "keyword", line.substring(nameStart, pos));
        }
        while (pos < len && line.charAt(pos) != '>') {
            char c = line.charAt(pos);
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
                pos = end;
                continue;
            }
            if (Character.isLetter(c) || c == ':' || c == '_') {
                int end = pos;
                while (end < len && (Character.isLetterOrDigit(line.charAt(end))
                        || ":-_.".indexOf(line.charAt(end)) >= 0)) {
                    end++;
                }
                String attr = line.substring(pos, end);
                if (attr.indexOf(':') >= 0 || attr.startsWith("hx-") || attr.startsWith("data-")) {
                    flush(out, plain);
                    SyntaxSpans.span(out, "meta", attr);
                } else {
                    plain.append(attr);
                }
                pos = end;
                continue;
            }
            plain.append(c);
            pos++;
        }
        if (pos < len && line.charAt(pos) == '>') {
            plain.append('>');
            pos++;
        }
        flush(out, plain);
        return pos;
    }

    private static void flush(StringBuilder out, StringBuilder plain) {
        if (plain.length() > 0) {
            SyntaxSpans.escape(out, plain.toString());
            plain.setLength(0);
        }
    }
}
