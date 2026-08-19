package io.tesseraql.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bearer-secret primitives for the store: codes and refresh tokens are generated with 256 bits
 * of entropy and persisted only as SHA-256 hex, so the store never holds a value a reader could
 * replay. Hashing (not constant-time comparison) is enough here because lookup is by hash key —
 * the same reasoning the API-key store recorded.
 */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    /** A fresh 256-bit token, base64url — the wire form of a code or refresh token. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    /** The store key of a bearer secret: SHA-256 hex of its wire form. */
    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
