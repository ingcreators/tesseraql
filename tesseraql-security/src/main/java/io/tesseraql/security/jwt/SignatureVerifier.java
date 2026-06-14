package io.tesseraql.security.jwt;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.policy.PolicyEngine;

/**
 * Verifies the signature of a JWT for one algorithm (design ch. 11.1). The verifier holds the key
 * material it needs (an HMAC secret, an RSA key, or a JWKS key source), so {@link JwtAuthenticator}
 * stays algorithm-agnostic: it binds the expected algorithm from configuration, checks it against
 * the token header before any key is touched, then delegates the cryptographic check here.
 *
 * <p>This separation is what makes RS256/JWKS plug in behind the same authentication step as HS256.
 */
public interface SignatureVerifier {

    /** Authentication failure code shared by all verifiers ({@code TQL-SEC-4011}). */
    io.tesseraql.core.error.TqlErrorCode UNAUTHORIZED = PolicyEngine.UNAUTHORIZED;

    /**
     * Verifies {@code signature} over {@code signingInput} (the ASCII {@code header.payload}).
     *
     * @param signingInput the base64url {@code header} and {@code payload} joined by {@code '.'}
     * @param signature    the base64url-decoded signature bytes
     * @param kid          the token header {@code kid}, or {@code null} when absent
     * @throws TqlException {@link #UNAUTHORIZED} on any verification failure
     */
    void verify(String signingInput, byte[] signature, String kid);
}
