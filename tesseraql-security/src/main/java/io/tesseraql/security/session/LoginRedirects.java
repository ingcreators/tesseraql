package io.tesseraql.security.session;

/**
 * Sanitizes a post-login redirect target (the {@code next} of password login, the OIDC return URL,
 * the SAML RelayState). A target is honored only when it is a <em>same-origin absolute path</em> —
 * it must begin with a single {@code /} and carry no scheme/host and no header-splitting control
 * characters — so a crafted value can never send a freshly-authenticated browser off-site (an open
 * redirect) or inject a response header. Anything else falls back to a caller-chosen default.
 */
public final class LoginRedirects {

    private LoginRedirects() {
    }

    /** Whether {@code next} is a safe same-origin absolute path (e.g. {@code /_tesseraql/studio/ui}). */
    public static boolean isSafe(String next) {
        return next != null
                && next.startsWith("/")
                && !next.startsWith("//")
                && !next.startsWith("/\\")
                && next.indexOf('\n') < 0
                && next.indexOf('\r') < 0;
    }

    /** Returns {@code next} when it is a safe same-origin path, otherwise {@code fallback}. */
    public static String sanitize(String next, String fallback) {
        return isSafe(next) ? next : fallback;
    }
}
