package io.tesseraql.security.session;

/**
 * Minimal HTTP {@code Cookie} header parsing. Public since docs/duplication-consolidation.md
 * campaign 4: three surfaces carried byte-identical copies, one of them naming this class's
 * package-private visibility as the reason — and the copies had already drifted on whether a
 * value is trimmed.
 */
public final class Cookies {

    private Cookies() {
    }

    /** Returns the value of the named cookie from a {@code Cookie} header, or {@code null}. */
    public static String value(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).equals(name)) {
                return trimmed.substring(eq + 1);
            }
        }
        return null;
    }
}
