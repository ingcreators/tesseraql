package io.tesseraql.core.text;

/**
 * The markup escapers every generated-HTML and generated-XML writer shares
 * (docs/duplication-consolidation.md, campaign 4). Ten private copies existed, and they
 * disagreed on the one character that turns a text escaper into an attribute hole: two dropped
 * the double quote, which is unsafe the moment output lands inside a quoted attribute — one of
 * them was already hand-writing {@code &quot;} entities to work around its own escaper.
 *
 * <p>Two grammars, three intents: HTML (text and double-quoted attributes escape the same
 * four), and XML split into text nodes (three) and double-quoted attribute values (four) — the
 * discipline the SAML writers already followed. Deliberately local escapers stay where they
 * are: Prometheus exposition, Markdown tables, and the JSON envelope are different grammars.
 * Null is refused ({@code NullPointerException}), as at nearly every prior site: a null
 * reaching an escaper is the caller's bug, and the two callers with genuinely optional values
 * keep their own guards.
 */
public final class Escapes {

    private Escapes() {
    }

    /** HTML text and double-quoted attribute values: {@code & < > "}. */
    public static String html(String value) {
        return xmlAttribute(value);
    }

    /** XML text nodes: {@code & < >}. */
    public static String xmlText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** XML double-quoted attribute values: {@code & < > "}. */
    public static String xmlAttribute(String value) {
        return xmlText(value).replace("\"", "&quot;");
    }
}
