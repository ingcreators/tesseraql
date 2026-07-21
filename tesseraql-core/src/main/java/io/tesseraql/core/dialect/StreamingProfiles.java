package io.tesseraql.core.dialect;

import java.util.Locale;

/**
 * The {@link StreamingProfile} for each dialect (design ch. 42, 28). PostgreSQL needs auto-commit off
 * with a positive fetch size to open a cursor; MySQL streams row-by-row with {@link Integer#MIN_VALUE};
 * the others use a positive fetch size. Unknown dialects get a conservative default.
 */
public final class StreamingProfiles {

    private static final StreamingProfile DEFAULT = new StreamingProfile(1000, false);

    private StreamingProfiles() {
    }

    /** The streaming profile for a dialect id, or the default when unknown. */
    public static StreamingProfile forDialect(String dialect) {
        if (dialect == null) {
            return DEFAULT;
        }
        return switch (dialect.toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> new StreamingProfile(1000, true);
            case "mysql", "mariadb" -> new StreamingProfile(Integer.MIN_VALUE, false);
            case "oracle", "sqlserver" -> new StreamingProfile(1000, false);
            // In-process engine: results stream natively, no cursor-mode autocommit dance.
            case "duckdb" -> new StreamingProfile(1000, false);
            default -> DEFAULT;
        };
    }
}
