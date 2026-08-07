package io.tesseraql.studio;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page builder's eligibility rule and round-trip split (docs/page-builder.md D1) —
 * the {@link MailComposer} sibling for hand-owned page templates. Two shapes open in the
 * builder:
 *
 * <ol>
 *   <li><b>shell-wrapped page</b> — prelude (doctype/comments), an {@code <html …
 *       th:replace="~{tql/shell :: …}">} open tag, comments, exactly one
 *       {@code div#page-content}, {@code </html>}. Everything outside the div's inner
 *       HTML is captured <em>verbatim</em> as prefix/suffix — the scaffold-checksum
 *       header and the wrapper survive the round trip byte-for-byte; only the region the
 *       canvas edits is re-serialized.</li>
 *   <li><b>bare fragment file</b> — no {@code <html>} root: the whole content is the
 *       region (prefix and suffix are empty).</li>
 * </ol>
 *
 * <p>Anything else is ineligible: the builder opens it read-only with the source editor
 * as the escape hatch — the same no-lossy-rewrite contract as the mail composer.
 */
public final class PageBuilder {

    /** The split: {@code prefix + region + suffix} reassembles the file byte-for-byte. */
    public record Parts(String prefix, String region, String suffix, boolean shellWrapped) {

        /** The class attribute of the {@code #page-content} div (canvas fidelity). */
        public String regionClass() {
            if (!shellWrapped) {
                return "";
            }
            Matcher matcher = CLASS_ATTR.matcher(prefix.substring(prefix.lastIndexOf("<div")));
            return matcher.find() ? matcher.group(1) : "";
        }
    }

    private static final Pattern CLASS_ATTR = Pattern.compile("class=\"([^\"]*)\"");
    private static final Pattern SHELL_WRAPPER = Pattern
            .compile("th:replace=\"~\\{tql/shell\\s*::");
    private static final Pattern PAGE_DIV = Pattern.compile("id=\"page-content\"");

    private PageBuilder() {
    }

    /** Splits an eligible template, or empty when the builder must not touch it. */
    public static Optional<Parts> parse(String template) {
        if (template == null || template.isBlank()) {
            return Optional.empty();
        }
        // The builder page seeds the canvas from an inert <template> element the server
        // fills verbatim — a region carrying its own </template> would break out of it,
        // so such files stay with the source editor.
        if (template.toLowerCase(java.util.Locale.ROOT).contains("</template")) {
            return Optional.empty();
        }
        int htmlStart = tagStart(template, 0, "html");
        if (htmlStart < 0) {
            // No <html> root anywhere: the whole file is the region (a fragment file).
            return Optional.of(new Parts("", template, "", false));
        }
        if (!isPassive(template.substring(0, htmlStart))) {
            return Optional.empty();
        }
        int htmlOpenEnd = tagEnd(template, htmlStart);
        if (htmlOpenEnd < 0
                || !SHELL_WRAPPER.matcher(template.substring(htmlStart, htmlOpenEnd)).find()) {
            return Optional.empty();
        }
        int divStart = tagStart(template, htmlOpenEnd, "div");
        if (divStart < 0 || !isPassive(template.substring(htmlOpenEnd, divStart))) {
            return Optional.empty();
        }
        int divOpenEnd = tagEnd(template, divStart);
        if (divOpenEnd < 0
                || !PAGE_DIV.matcher(template.substring(divStart, divOpenEnd)).find()) {
            return Optional.empty();
        }
        int divClose = matchingDivClose(template, divOpenEnd);
        if (divClose < 0) {
            return Optional.empty();
        }
        String suffix = template.substring(divClose);
        String afterClose = suffix.substring("</div>".length());
        int htmlClose = afterClose.indexOf("</html>");
        if (htmlClose < 0 || !isPassive(afterClose.substring(0, htmlClose))
                || !afterClose.substring(htmlClose + "</html>".length()).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Parts(template.substring(0, divOpenEnd),
                template.substring(divOpenEnd, divClose), suffix, true));
    }

    /** Only whitespace, doctype, and comments — the connective tissue the split keeps. */
    private static boolean isPassive(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                if (end < 0) {
                    return false;
                }
                i = end + 3;
            } else if (text.startsWith("<!", i)) {
                int end = text.indexOf('>', i);
                if (end < 0) {
                    return false;
                }
                i = end + 1;
            } else {
                return false;
            }
        }
        return true;
    }

    /** The offset of the next {@code <name}-tag open outside comments, or -1. */
    private static int tagStart(String text, int from, String name) {
        String needle = "<" + name;
        int i = from;
        while (i < text.length()) {
            if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                if (end < 0) {
                    return -1;
                }
                i = end + 3;
                continue;
            }
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                char next = i + needle.length() < text.length()
                        ? text.charAt(i + needle.length())
                        : ' ';
                if (Character.isWhitespace(next) || next == '>') {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** The offset just past the open tag's {@code >}, quote-aware; -1 when unclosed. */
    private static int tagEnd(String text, int from) {
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * The offset of the {@code </div>} closing the div whose open tag ends at
     * {@code from}, depth-counting nested divs outside comments and attribute quotes.
     */
    private static int matchingDivClose(String text, int from) {
        int depth = 1;
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '<') {
                i++;
                continue;
            }
            if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                if (end < 0) {
                    return -1;
                }
                i = end + 3;
                continue;
            }
            if (text.regionMatches(true, i, "</div>", 0, 6)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
                i += 6;
                continue;
            }
            boolean divOpen = text.regionMatches(true, i, "<div", 0, 4)
                    && i + 4 < text.length()
                    && (Character.isWhitespace(text.charAt(i + 4)) || text.charAt(i + 4) == '>');
            // Jump every tag with the quote-aware scan, so a '<div' or '</div>' inside an
            // attribute value can never miscount the depth.
            int end = tagEnd(text, i);
            if (end < 0) {
                return -1;
            }
            if (divOpen) {
                // A self-closing <div/> would not nest, but hc markup never emits one.
                depth++;
            }
            i = end;
        }
        return -1;
    }
}
