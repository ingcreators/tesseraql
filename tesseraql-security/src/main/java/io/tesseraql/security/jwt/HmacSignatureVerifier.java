package io.tesseraql.security.jwt;

import io.tesseraql.core.error.TqlException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies an HS256 JWT signature with a shared HMAC secret (design ch. 11.1 {@code bearer}). The
 * token header {@code kid} is irrelevant to a symmetric secret and is ignored.
 */
public final class HmacSignatureVerifier implements SignatureVerifier {

    private final String secret;

    public HmacSignatureVerifier(String secret) {
        this.secret = secret;
    }

    @Override
    public void verify(String signingInput, byte[] signature, String kid) {
        if (secret == null || secret.isBlank()) {
            throw new TqlException(UNAUTHORIZED, "JWT secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, signature)) {
                throw new TqlException(UNAUTHORIZED, "Invalid JWT signature");
            }
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(UNAUTHORIZED, "JWT signature verification failed");
        }
    }
}
