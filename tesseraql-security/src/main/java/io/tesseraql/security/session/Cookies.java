package io.tesseraql.security.session;

/** Minimal HTTP {@code Cookie} header parsing. */
final class Cookies {

    private Cookies() {
    }

    /** Returns the value of the named cookie from a {@code Cookie} header, or {@code null}. */
    static String value(String cookieHeader, String name) {
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
