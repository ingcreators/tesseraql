package io.tesseraql.security.totp;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords over {@code javax.crypto.Mac} (roadmap Phase 50
 * slice 3): HmacSHA1, 6 digits, 30-second steps — exactly what authenticator apps default
 * to, with no new dependency. Verification returns the MATCHED step so the caller can
 * record it: the store's compare-and-set on {@code last_used_step} is the replay guard.
 */
public final class Totp {

    /** The verification window: the current step ± this many neighbours. */
    public static final int WINDOW = 1;

    private static final long STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A fresh 160-bit secret, Base32 (the alphabet authenticator apps expect). */
    public static String generateSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return Base32.encode(secret);
    }

    /** The current 30-second step. */
    public static long currentStep() {
        return System.currentTimeMillis() / 1000 / STEP_SECONDS;
    }

    /**
     * The step (within {@code currentStep() ± WINDOW}) whose code matches, or -1. The
     * caller must then win the store's last-used-step compare-and-set before accepting —
     * that, not this, is what stops a captured code replaying inside its window.
     */
    public static long matchedStep(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = Base32.decode(base32Secret);
        long now = currentStep();
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            if (constantTimeEquals(codeAt(key, step), code)) {
                return step;
            }
        }
        return -1;
    }

    /** The code for a step — public so tests and provisioning tools can compute one. */
    public static String codeAt(String base32Secret, long step) {
        return codeAt(Base32.decode(base32Secret), step);
    }

    /** The otpauth:// URI authenticator apps import (manual entry: the secret itself). */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String safeIssuer = uriComponent(issuer);
        return "otpauth://totp/" + safeIssuer + ":" + uriComponent(account)
                + "?secret=" + base32Secret + "&issuer=" + safeIssuer
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] counter = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counter[i] = (byte) (step & 0xff);
                step >>>= 8;
            }
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA1 unavailable", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String uriComponent(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
