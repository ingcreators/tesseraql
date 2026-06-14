package io.tesseraql.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) and the random tokens the OIDC flow needs, JDK-only. The {@code code_verifier},
 * {@code state}, and {@code nonce} are each a fresh 256-bit URL-safe random; the {@code S256}
 * {@code code_challenge} is {@code base64url(SHA-256(code_verifier))}.
 */
public final class Pkce {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    /** A fresh high-entropy URL-safe token (43 chars), used for {@code state} and {@code nonce}. */
    public static String token() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    /** A fresh PKCE {@code code_verifier} (RFC 7636 §4.1): 43 base64url chars, 256 bits of entropy. */
    public static String verifier() {
        return token();
    }

    /** The {@code S256} {@code code_challenge} for a verifier (RFC 7636 §4.2). */
    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
