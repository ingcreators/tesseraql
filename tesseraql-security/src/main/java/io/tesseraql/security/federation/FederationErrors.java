package io.tesseraql.security.federation;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;

/**
 * The error vocabulary the OIDC and SAML endpoints answer with
 * (docs/framework-surface-parity.md).
 *
 * <p>Both returned {@code {"error": "<string>"}} — a shape no other endpoint uses, carrying no
 * code an operator could search for — and both wrapped every failure in
 * {@code onException(Exception.class).handled(true)} that answered 400. So a broken IdP, an
 * unreachable JWKS endpoint and a genuinely malformed callback were reported identically, as the
 * caller's fault. The one thing an operator most needs to know at a federation boundary — whose
 * fault is this — was the thing the response hid.
 *
 * <p>Shared rather than duplicated in each module because the two surfaces are the same contract
 * seen twice; that is the deviation the parity document exists to remove.
 */
public final class FederationErrors {

    /** The assertion, code or state did not authenticate the caller (401). */
    public static final TqlErrorCode UNAUTHENTICATED = new TqlErrorCode(TqlDomain.SEC, 4011);

    /** The federation exchange failed on our side or the IdP's (500), not the caller's. */
    public static final TqlErrorCode FAILED = new TqlErrorCode(TqlDomain.SEC, 4140);

    private FederationErrors() {
    }

    /**
     * The framework's error envelope as JSON.
     *
     * <p>Built by hand rather than through a mapper because these two builders answer before any
     * route context exists, and the message is a fixed phrase — never an exception's text, which
     * is what the previous concatenation leaked.
     */
    public static String body(TqlErrorCode code, String message) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}}";
    }

    /** Only fixed phrases reach this, but a phrase is still text: keep the JSON valid. */
    private static String escape(String message) {
        StringBuilder escaped = new StringBuilder(message.length() + 8);
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
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
