package io.tesseraql.core.error;

/**
 * The framework's error envelope, {@code {"error":{"code":…,"message":…}}}, as JSON
 * (docs/duplication-consolidation.md, campaign 3). Six surfaces spelled it by string
 * concatenation before any route context exists — the edge's unrendered failure, the admission
 * and body-limit refusals, the SSE refusal, the reload stub, the relay — each with its own or no
 * escaping, and they had drifted: one dropped the {@code message} key entirely. The one correct
 * copy (the federation endpoints', which escaped quotes, backslashes and control characters)
 * moved here.
 *
 * <p>Built by hand rather than through a mapper because these writers answer before any route
 * context exists, and the message is a fixed phrase — never an exception's text, which is what
 * hand concatenation leaked historically. What status rides with the envelope, and any
 * {@code Retry-After}, stays with each surface: the values genuinely differ (an SSE reconnect
 * hint is not a login throttle's wait).
 */
public final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    /** The envelope for a typed code. */
    public static String json(TqlErrorCode code, String message) {
        return json(code.toString(), message);
    }

    /**
     * The envelope for a code held in a {@code String} constant — the lint families' idiom
     * (docs/lint-restructure.md decision 4), which the relay's constants share.
     */
    public static String json(String code, String message) {
        return "{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\"}}";
    }

    /** Only fixed phrases reach this, but a phrase is still text: keep the JSON valid. */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
