package io.tesseraql.core.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Identifies a structural variant of a 2-way SQL template (design ch. 8.2, 14, 46.8).
 *
 * <p>Two renders of the same {@code .sql} file produce the same variant if and only if the same
 * set of conditional branches was taken, regardless of bind values. The {@link #hash()} is a
 * stable digest used as a key for coverage aggregation and query-plan baselines.
 *
 * @param key  a human-readable canonical description of the branch decisions
 * @param hash the hex SHA-256 digest of {@code key}
 */
public record SqlVariant(String key, String hash) {

    /** Builds a variant from the ordered branch decisions recorded during rendering. */
    public static SqlVariant of(List<CoverageTrace.Branch> branches) {
        StringBuilder sb = new StringBuilder();
        for (CoverageTrace.Branch branch : branches) {
            sb.append("b@").append(branch.sourceLine()).append('=').append(branch.taken())
                    .append(';');
        }
        String key = sb.toString();
        return new SqlVariant(key, sha256(key));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
