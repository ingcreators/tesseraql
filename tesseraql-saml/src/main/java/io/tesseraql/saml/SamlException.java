package io.tesseraql.saml;

/**
 * A SAML SP processing failure (design ch. 10.14): a malformed, unsigned, untrusted, or expired
 * assertion, or any validation rule that did not hold. Carrying a clear message but never the raw
 * assertion, so secrets are not leaked into logs.
 */
public final class SamlException extends RuntimeException {

    public SamlException(String message) {
        super(message);
    }

    public SamlException(String message, Throwable cause) {
        super(message, cause);
    }
}
