package io.tesseraql.core.mask;

/**
 * Data masking strategies for sensitive values (design ch. 34.3).
 *
 * <p>Masking is applied to outputs, logs, traces, and reports so that classified data
 * (PII, credentials, secrets) is never exposed in clear text. Strategies are referenced by name
 * from field policies and classification defaults.
 */
public final class Masking {

    /** The fixed replacement used by the {@code fixed} strategy and as the safe default. */
    public static final String FIXED = "[MASKED]";

    private Masking() {
    }

    /**
     * Masks a value using the named strategy. A {@code null} value stays {@code null}; an unknown
     * strategy falls back to {@link #FIXED} so masking fails safe.
     *
     * @param strategy one of {@code email}, {@code last4}, {@code fixed}
     * @param value    the value to mask
     */
    public static Object apply(String strategy, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return switch (strategy == null ? "fixed" : strategy) {
            case "email" -> email(text);
            case "last4" -> last4(text);
            default -> FIXED;
        };
    }

    private static String email(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return FIXED;
        }
        char first = value.charAt(0);
        return first + "***" + value.substring(at);
    }

    private static String last4(String value) {
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    /**
     * Returns the default masking action for a data classification (design ch. 34.3):
     * {@code hide} for credentials/secrets, {@code mask} for PII and business-confidential,
     * otherwise {@code null} (no masking).
     */
    public static String defaultActionFor(String classification) {
        if (classification == null) {
            return null;
        }
        return switch (classification) {
            case "credential", "secret" -> "hide";
            case "pii", "business-confidential", "regulated" -> "mask";
            default -> null;
        };
    }
}
