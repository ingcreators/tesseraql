package io.tesseraql.security.password;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;

/**
 * Verifies a raw password against a stored hash, dispatching on the algorithm (design ch. 10.8).
 * PBKDF2 is supported now; Argon2id and bcrypt are added behind this facade.
 */
public final class PasswordVerifier {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.SEC, 5002);

    private final Pbkdf2PasswordEncoder pbkdf2 = new Pbkdf2PasswordEncoder();

    /**
     * Verifies a password.
     *
     * @param raw    the raw password
     * @param hash   the stored hash
     * @param algo   the algorithm ({@code pbkdf2}); null defaults to pbkdf2
     * @param params the stored parameters, e.g. {@code iterations=100000,keyLength=256}
     */
    public boolean verify(String raw, String hash, String algo, String params) {
        if (raw == null || hash == null) {
            return false;
        }
        String algorithm = algo == null || algo.isBlank() ? "pbkdf2" : algo.toLowerCase();
        if ("pbkdf2".equals(algorithm)) {
            return pbkdf2.matches(raw, hash,
                    param(params, "iterations", Pbkdf2PasswordEncoder.DEFAULT_ITERATIONS),
                    param(params, "keyLength", Pbkdf2PasswordEncoder.DEFAULT_KEY_LENGTH));
        }
        throw new TqlException(UNSUPPORTED, "Unsupported password algorithm: " + algorithm);
    }

    private static int param(String params, String key, int defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        for (String part : params.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) {
                return Integer.parseInt(kv[1].trim());
            }
        }
        return defaultValue;
    }
}
