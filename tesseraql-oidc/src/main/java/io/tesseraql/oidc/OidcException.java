package io.tesseraql.oidc;

/**
 * An OIDC relying-party processing failure (design ch. 10.14, roadmap Phase 25): a failed discovery
 * or token exchange, an invalid ID token (bad signature, issuer, audience, nonce, or expiry), or a
 * mismatched/replayed {@code state}. Carries a clear message but never the raw token, code, or
 * client secret, so secrets are not leaked into logs.
 */
public final class OidcException extends RuntimeException {

    public OidcException(String message) {
        super(message);
    }

    public OidcException(String message, Throwable cause) {
        super(message, cause);
    }
}
