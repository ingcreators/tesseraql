package io.tesseraql.core.text;

/**
 * The markup escapers every generated-HTML and generated-XML writer shares
 * (docs/duplication-consolidation.md, campaign 4). Ten private copies existed, and they
 * disagreed on the one character that turns a text escaper into an attribute hole: two dropped
 * the double quote, which is unsafe the moment output lands inside a quoted attribute — one of
 * them was already hand-writing {@code &quot;} entities to work around its own escaper.
 *
 * <p>Two grammars, three escapers: HTML (text and double-quoted attribute values), and XML
 * split into text nodes (three) and double-quoted attribute values (four) — the discipline the
 * SAML writers already followed. The XML escapers also substitute the characters XML 1.0
 * cannot carry at all — the C0 controls except tab, LF, and CR are illegal even as character
 * references — with U+FFFD, so an exception message holding one no longer turns a whole JUnit
 * report invalid. Deliberately local escapers stay where they are: Prometheus exposition,
 * Markdown tables, and the JSON envelope are different grammars. Null is refused
 * ({@code NullPointerException}), as at nearly every prior site: a null reaching an escaper is
 * the caller's bug, and the two callers with genuinely optional values keep their own guards.
 */
public final class Escapes {

    private Escapes() {
    }

    /**
     * HTML text and <em>double-quoted</em> attribute values: {@code & < > "}. The single quote
     * is deliberately not escaped — no framework writer emits single-quoted attributes, and the
     * one writer placing text inside a single-quoted OGNL literal escapes for that grammar
     * itself first — so this is its own escaper rather than an alias of the XML attribute one:
     * the two grammars agree on four characters today, not by contract.
     */
    public static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** XML text nodes: {@code & < >}; XML-illegal control characters become U+FFFD. */
    public static String xmlText(String value) {
        return substituteIllegal(
                value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }

    /** XML double-quoted attribute values: {@code & < > "}; illegal controls become U+FFFD. */
    public static String xmlAttribute(String value) {
        return xmlText(value).replace("\"", "&quot;");
    }

    /**
     * XML 1.0 cannot carry the C0 controls (save tab, LF, CR) even as character references;
     * U+FFFD keeps the document well-formed and marks that something unrepresentable was there.
     */
    private static String substituteIllegal(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean illegal = c < 0x20 && c != '\t' && c != '\n' && c != '\r';
            if (illegal && out == null) {
                out = new StringBuilder(value.length()).append(value, 0, i);
            }
            if (out != null) {
                out.append(illegal ? '\uFFFD' : c);
            }
        }
        return out == null ? value : out.toString();
    }
}
