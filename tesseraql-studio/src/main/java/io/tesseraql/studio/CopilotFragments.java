package io.tesseraql.studio;

/**
 * The copilot panel's {@code hc-chat} markup, built in exactly one place (design in
 * docs/copilot.md, "One source for the transcript markup"): the page render inserts these
 * strings per entry with {@code th:utext}, and the SSE {@code done} event carries the same
 * strings — so a streamed turn and a later page reload render identically, with no
 * template/Java drift to guard. Every model- or user-authored string is escaped here; the
 * client appends, never interprets.
 *
 * <p>The item and placeholder shapes follow the kit's {@code chat-messages} and
 * {@code streaming-response} recipe contracts (hc 0.1.9): chunks append into the
 * placeholder's {@code hc-chat__body}, and {@code done}/{@code error} swap the whole
 * {@code <li>} away — which also closes the EventSource.
 */
public final class CopilotFragments {

    private CopilotFragments() {
    }

    /** One transcript item — the same markup on the page and in the {@code done} event. */
    public static String entryHtml(CopilotService.Entry entry) {
        StringBuilder html = new StringBuilder(64);
        html.append("<li class=\"hc-chat__message\" data-role=\"")
                .append("user".equals(entry.role()) ? "user" : "assistant").append("\">");
        if (entry.tool() != null) {
            html.append("<p class=\"hc-field__message\">used: ").append(escape(entry.tool()))
                    .append("</p>");
        }
        html.append("<div class=\"hc-chat__body\">").append(text(entry.text()))
                .append("</div></li>");
        return html.toString();
    }

    /**
     * The streaming assistant placeholder (streaming-response recipe): it owns its own SSE
     * connection, {@code chunk} events append into its body, and {@code done}/{@code error}
     * swap it away — {@code aria-busy} defers the announcement until then.
     */
    public static String placeholder(String streamUrl) {
        return "<li class=\"hc-chat__message\" data-role=\"assistant\" data-state=\"streaming\""
                + " aria-busy=\"true\" hx-ext=\"sse\" sse-connect=\"" + escape(streamUrl) + "\""
                + " sse-swap=\"done,error\" hx-swap=\"outerHTML\">"
                + "<div class=\"hc-chat__body\" sse-swap=\"chunk\" hx-swap=\"beforeend\"></div>"
                + "</li>";
    }

    /** The final item a failed turn swaps over its placeholder ({@code error} event). */
    public static String errorHtml(String message) {
        return "<li class=\"hc-chat__message\" data-role=\"assistant\" data-state=\"error\">"
                + "<div class=\"hc-chat__body\">" + text(message) + "</div></li>";
    }

    /**
     * The composer's message input — the piece a successful htmx send re-renders
     * out-of-band so the box clears (chat-messages recipe). The page template inserts the
     * non-oob variant inside its form, so the swap can never drift from the initial markup;
     * the form itself (action, csrf, htmx wiring) stays template-authored where {@code
     * _csrf} lives.
     */
    public static String messageInput(boolean oob) {
        return "<input id=\"copilot-message\" class=\"hc-input hc-field--grow\""
                + " name=\"message\" required maxlength=\"4000\""
                + " placeholder=\"Describe the route, page or fix you want&hellip;\" autofocus"
                + (oob ? " hx-swap-oob=\"true\"" : "") + ">";
    }

    /** A model content delta, escaped and single-lined for an SSE {@code data:} frame. */
    public static String textChunk(String delta) {
        return text(delta);
    }

    /** A small marker chunk emitted when the loop executes a tool mid-stream. */
    public static String toolChunk(String name) {
        return "<em class=\"hc-field__message\">&#8594; " + escape(name) + "</em><br>";
    }

    /** Escaped text with newlines as markup — SSE data lines must stay single-line. */
    private static String text(String raw) {
        return raw == null ? "" : escape(raw).replace("\r\n", "\n").replace("\n", "<br>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
