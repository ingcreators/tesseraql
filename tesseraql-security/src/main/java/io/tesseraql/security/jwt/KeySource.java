package io.tesseraql.security.jwt;

import java.security.interfaces.RSAPublicKey;

/**
 * Supplies the RSA public key that verifies an RS256 JWT, selected by the token header {@code kid}
 * (design ch. 11.1). A static configuration exposes a single key; a JWKS source exposes a set keyed
 * by {@code kid} and refreshes on rotation.
 */
public interface KeySource {

    /**
     * The key for {@code kid}, or the sole key when {@code kid} is {@code null}.
     *
     * @throws io.tesseraql.core.error.TqlException {@link SignatureVerifier#UNAUTHORIZED} when no
     *                                              key matches (an unknown or ambiguous {@code kid})
     */
    RSAPublicKey resolve(String kid);
}
