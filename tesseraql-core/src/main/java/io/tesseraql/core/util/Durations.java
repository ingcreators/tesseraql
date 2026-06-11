package io.tesseraql.core.util;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.time.Duration;

/**
 * Parses the short duration strings used throughout TesseraQL configuration, for example
 * {@code 5s}, {@code 100ms}, {@code 30m}, {@code 8h} (design ch. 11.2, 24.3, 28.5).
 */
public final class Durations {

    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.YAML, 1301);

    private Durations() {
    }

    /** Parses a duration string such as {@code 5s} or {@code 100ms}. */
    public static Duration parse(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new TqlException(INVALID, "Empty duration");
        }
        int unitStart = 0;
        while (unitStart < trimmed.length() && Character.isDigit(trimmed.charAt(unitStart))) {
            unitStart++;
        }
        if (unitStart == 0) {
            throw new TqlException(INVALID, "Duration must start with a number: " + value);
        }
        long amount = Long.parseLong(trimmed.substring(0, unitStart));
        String unit = trimmed.substring(unitStart).toLowerCase();
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s", "" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new TqlException(INVALID, "Unknown duration unit '" + unit + "' in " + value);
        };
    }

    /** Parses a duration to milliseconds. */
    public static long toMillis(String value) {
        return parse(value).toMillis();
    }
}
