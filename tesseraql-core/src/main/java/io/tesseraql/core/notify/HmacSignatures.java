package io.tesseraql.core.notify;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signatures for outbound webhooks (roadmap Phase 20).
 *
 * <p>The signature covers {@code <timestamp>.<body>} so a receiver can both authenticate the
 * payload and bound replay: recompute the HMAC over the received timestamp header and raw body,
 * compare in constant time, and reject stale timestamps. The header value is
 * {@code sha256=<lowercase hex>}.
 */
public final class HmacSignatures {

    /** The signature header sent with every webhook delivery. */
    public static final String SIGNATURE_HEADER = "X-TesseraQL-Signature";
    /** The epoch-seconds timestamp header covered by the signature. */
    public static final String TIMESTAMP_HEADER = "X-TesseraQL-Timestamp";

    private static final String PREFIX = "sha256=";

    private HmacSignatures() {
    }

    /** Signs {@code <timestamp>.<body>}, returning the {@code sha256=<hex>} header value. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return PREFIX + hex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", ex);
        }
    }

    /** Verifies a received signature in constant time. */
    public static boolean verify(String secret, String timestamp, byte[] body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
