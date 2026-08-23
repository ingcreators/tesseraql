package io.tesseraql.core.util;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Locale;

/**
 * Parses the byte-size strings used throughout TesseraQL configuration, for example
 * {@code 25MB}, {@code 512KB}, or {@code 1048576} (bare bytes), the way {@link Durations}
 * parses the short duration strings.
 */
public final class Sizes {

    /**
     * TQL-YAML-1302: a configured size is not {@code <number>} or {@code <number><unit>} with
     * unit B/KB/MB/GB, for example {@code 25MB}.
     */
    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.YAML, 1302);

    private Sizes() {
    }

    /** Parses a size string such as {@code 25MB} or {@code 1048576} into a byte count. */
    public static long parseBytes(String value) {
        return parseBytes(value, null);
    }

    /**
     * Parses like {@link #parseBytes(String)}, naming {@code subject} — the configuration key
     * or setting being read — in the refusal, so a boot failure says which key to fix.
     */
    public static long parseBytes(String value, String subject) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new TqlException(INVALID, prefix(subject) + "Empty size");
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        long multiplier = 1;
        if (upper.endsWith("KB")) {
            multiplier = 1024L;
            upper = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("MB")) {
            multiplier = 1024L * 1024L;
            upper = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("GB")) {
            multiplier = 1024L * 1024L * 1024L;
            upper = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("B")) {
            upper = upper.substring(0, upper.length() - 1);
        }
        long number;
        try {
            number = Long.parseLong(upper.trim());
        } catch (NumberFormatException ex) {
            throw new TqlException(INVALID, prefix(subject)
                    + "Size must be a number with an optional B/KB/MB/GB unit: " + value);
        }
        if (number < 0) {
            throw new TqlException(INVALID,
                    prefix(subject) + "Size must not be negative: " + value);
        }
        return number * multiplier;
    }

    private static String prefix(String subject) {
        return subject == null ? "" : subject + ": ";
    }
}
