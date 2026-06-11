package io.tesseraql.security.password;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2 password hashing (design ch. 10.8.1). PBKDF2 is dependency-free (JDK); Argon2id and bcrypt
 * verifiers plug in behind {@link PasswordVerifier} later. The stored hash is
 * {@code base64(salt):base64(derivedKey)}.
 */
public final class Pbkdf2PasswordEncoder {

    public static final int DEFAULT_ITERATIONS = 100_000;
    public static final int DEFAULT_KEY_LENGTH = 256;

    private static final TqlErrorCode ENCODE_ERROR = new TqlErrorCode(TqlDomain.SEC, 5001);
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Encodes a raw password with the default parameters, returning {@code base64(salt):base64(dk)}. */
    public String encode(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = derive(raw, salt, DEFAULT_ITERATIONS, DEFAULT_KEY_LENGTH);
        return Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(dk);
    }

    /** The {@code password_params} string matching {@link #encode}. */
    public String defaultParams() {
        return "iterations=" + DEFAULT_ITERATIONS + ",keyLength=" + DEFAULT_KEY_LENGTH;
    }

    /** Verifies a raw password against a stored {@code base64(salt):base64(dk)} hash. */
    public boolean matches(String raw, String hash, int iterations, int keyLength) {
        int colon = hash.indexOf(':');
        if (colon < 0) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(hash.substring(0, colon));
        byte[] expected = Base64.getDecoder().decode(hash.substring(colon + 1));
        byte[] actual = derive(raw, salt, iterations, keyLength);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(String raw, byte[] salt, int iterations, int keyLength) {
        try {
            KeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, keyLength);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
                    .getEncoded();
        } catch (Exception ex) {
            throw new TqlException(ENCODE_ERROR, "PBKDF2 hashing failed: " + ex.getMessage());
        }
    }
}
