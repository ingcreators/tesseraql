package io.tesseraql.core.http;

import java.util.Objects;

/**
 * Percent-encoding for the two wire grammars the framework writes text into: an RFC 8187
 * {@code filename*} value and a URI reference. Hand-written, because this module carries no
 * dependency (the enforcer refuses even a runtime-scoped one).
 *
 * <p>One loop, two allow-lists. The loop walks Unicode code points, never UTF-16 units, so an
 * astral character is four octets rather than two {@code %3F}s; a lone surrogate is not a
 * character and leaves as U+FFFD, the replacement Unicode assigns to it — never as {@code ?},
 * which is the corruption this class exists to end, and never as an exception, because a
 * download name comes from an upload or a table and a refusal there is a 500 on a read. The
 * octets are UTF-8 computed here, not by a charset lookup: RFC 8187 section 3.2.1 says a
 * producer MUST use UTF-8, and the JVM's default charset (which the mail leg still trusts) is
 * not a contract. Hex digits are upper-case, as RFC 3986 section 2.1 asks of producers.
 *
 * <p>The two lists differ in what they leave alone, and that difference is each grammar's
 * idempotence rule. {@link #extValue} keeps only RFC 8187 {@code attr-char}: {@code %} is data
 * and becomes {@code %25}, so the method is applied exactly once, to decoded text.
 * {@link #uriLiteral} keeps every character RFC 3986 lets a URI reference spell, including an
 * authored {@code %XX} triplet: a URI reference is already a produced URI (RFC 3986 section 2.4
 * — never encode the same string twice), and only what no URI can carry — non-ASCII, space,
 * controls, the nine ASCII characters the RFC admits nowhere, and a {@code %} that starts no
 * triplet — is made representable. Applying it twice is applying it once.
 */
public final class PercentEncoding {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * RFC 8187 section 3.2.1: {@code attr-char = ALPHA / DIGIT / "!" / "#" / "$" / "&" / "+"
     * / "-" / "." / "^" / "_" / "`" / "|" / "~"} — 74 characters; token minus {@code *},
     * {@code '} and {@code %}, the three the ext-value grammar uses for itself.
     */
    private static final boolean[] ATTR_CHAR = allow(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                    + "!#$&+-.^_`|~");

    /**
     * What RFC 3986 lets a URI reference spell, {@code %} aside: {@code unreserved}, the
     * {@code gen-delims} and the {@code sub-delims}. The nine visible ASCII characters outside
     * this table ({@code " < > \ ^ ` { | }}) appear in no RFC 3986 production, and a recipient
     * that parses strictly — the JDK's own {@code java.net.URI} among them — refuses a reference
     * that carries one raw.
     */
    private static final boolean[] URI_CHAR = allow(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                    + "-._~:/?#[]@!$&'()*+,;=");

    private PercentEncoding() {
    }

    /**
     * {@code text} as RFC 8187 {@code value-chars}: every code point outside {@code attr-char}
     * becomes the percent-encoded octets of its UTF-8 form. Not idempotent by design — apply it
     * once, to decoded text, never to a value that already carries escapes of its own.
     */
    public static String extValue(String text) {
        return encode(text, ATTR_CHAR, false);
    }

    /**
     * {@code text} as the wire form of a URI reference the author (or the framework) already
     * spelled: every character a URI may carry stays, an authored {@code %XX} triplet stays,
     * and everything else — non-ASCII, space, controls, {@code " < > \ ^ ` { | }}, a lone
     * {@code %} — becomes the percent-encoded octets of its UTF-8 form. Idempotent.
     */
    public static String uriLiteral(String text) {
        return encode(text, URI_CHAR, true);
    }

    /**
     * Whether a response header's value is a URI-reference the client navigates to — the
     * headers {@link #uriLiteral} exists for. One predicate shared by the compiler, which encodes
     * a declared one, and the HTTP edge, which refuses one that reaches it un-encoded, so the two
     * can never disagree on the list (the {@link ReservedHeaders} precedent). {@code HX-Redirect}
     * is here because htmx assigns it to {@code window.location.href}, the same parser a browser
     * applies to {@code Location}.
     */
    public static boolean isUriReferenceHeader(String name) {
        return "Location".equalsIgnoreCase(name) || "HX-Redirect".equalsIgnoreCase(name);
    }

    private static String encode(String text, boolean[] verbatim, boolean keepTriplets) {
        Objects.requireNonNull(text, "text to percent-encode");
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                // codePointAt hands back the unit itself for an unpaired surrogate; it is not
                // a character and has no UTF-8 form (RFC 3629 section 3).
                cp = 0xFFFD;
            }
            if (cp < 0x80) {
                if (verbatim[cp] || (keepTriplets && cp == '%' && isTriplet(text, i))) {
                    out.append((char) cp);
                } else {
                    octet(out, cp);
                }
            } else if (cp < 0x800) {
                octet(out, 0xC0 | (cp >> 6));
                octet(out, 0x80 | (cp & 0x3F));
            } else if (cp < 0x10000) {
                octet(out, 0xE0 | (cp >> 12));
                octet(out, 0x80 | ((cp >> 6) & 0x3F));
                octet(out, 0x80 | (cp & 0x3F));
            } else {
                octet(out, 0xF0 | (cp >> 18));
                octet(out, 0x80 | ((cp >> 12) & 0x3F));
                octet(out, 0x80 | ((cp >> 6) & 0x3F));
                octet(out, 0x80 | (cp & 0x3F));
            }
            i = next;
        }
        return out.toString();
    }

    /** Whether the {@code %} at {@code at} starts a {@code pct-encoded} triplet. */
    private static boolean isTriplet(String text, int at) {
        return at + 2 < text.length() && isHexDig(text.charAt(at + 1))
                && isHexDig(text.charAt(at + 2));
    }

    /** RFC 3986 {@code HEXDIG}: ASCII only — {@code Character.digit} would admit any script's. */
    private static boolean isHexDig(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static void octet(StringBuilder out, int octet) {
        out.append('%').append(HEX[octet >> 4]).append(HEX[octet & 0xF]);
    }

    private static boolean[] allow(String characters) {
        boolean[] table = new boolean[128];
        for (int i = 0; i < characters.length(); i++) {
            table[characters.charAt(i)] = true;
        }
        return table;
    }
}
