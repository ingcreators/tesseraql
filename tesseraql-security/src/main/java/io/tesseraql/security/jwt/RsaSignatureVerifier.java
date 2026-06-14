package io.tesseraql.security.jwt;

import io.tesseraql.core.error.TqlException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * Verifies an RS256 JWT signature with {@code SHA256withRSA} (design ch. 11.1), JDK-only. The
 * public key is supplied by a {@link KeySource} keyed on the token header {@code kid}, so the same
 * verifier serves a pinned static key and a rotating JWKS endpoint.
 */
public final class RsaSignatureVerifier implements SignatureVerifier {

    private final KeySource keys;

    public RsaSignatureVerifier(KeySource keys) {
        this.keys = keys;
    }

    @Override
    public void verify(String signingInput, byte[] signature, String kid) {
        RSAPublicKey key = keys.resolve(kid);
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(key);
            rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!rsa.verify(signature)) {
                throw new TqlException(UNAUTHORIZED, "Invalid JWT signature");
            }
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(UNAUTHORIZED, "JWT signature verification failed");
        }
    }
}
