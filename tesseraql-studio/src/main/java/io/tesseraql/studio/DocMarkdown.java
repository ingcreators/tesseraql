package io.tesseraql.studio;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Server-side Markdown rendering for the documentation portal (documentation portal v1): the
 * generated spec Markdown and hand-written {@code docs/*.md}, rendered with a standard library
 * (commonmark-java, Apache-2.0) rather than a hand-rolled parser.
 *
 * <p>The output satisfies Studio's strict CSP ({@code default-src 'self'}, no inline script): raw
 * HTML in the source is escaped (so a stray {@code <script>} cannot reach the page) and link/image
 * URLs are sanitized (so {@code javascript:} targets are neutralized). Placed into templates with
 * {@code th:utext}; any code highlighting is server-side classes plus hc styles, never inline JS.
 */
public final class DocMarkdown {

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    private DocMarkdown() {
    }

    /** Renders Markdown to CSP-safe HTML (raw HTML escaped, URLs sanitized); blank in, blank out. */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return RENDERER.render(PARSER.parse(markdown));
    }
}
