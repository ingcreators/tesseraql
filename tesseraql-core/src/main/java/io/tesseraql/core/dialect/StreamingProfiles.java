package io.tesseraql.core.dialect;

import java.util.Locale;

/**
 * The {@link StreamingProfile} for each dialect (design ch. 42, 28). PostgreSQL needs auto-commit off
 * with a positive fetch size to open a cursor; MySQL streams row-by-row with {@link Integer#MIN_VALUE};
 * the others, MariaDB included, use a positive fetch size. Unknown dialects get a conservative
 * default.
 *
 * <p>A dialect answers only what the SQL needs. Whether a <em>driver</em> accepts the fetch size
 * that implies is a separate question, and a dialect cannot answer it: MariaDB speaks MySQL's
 * protocol, so its URL infers {@link Dialect#MYSQL}, yet its driver rejects MySQL's row-streaming
 * sentinel. {@code SqlStatement} settles that at the connection. Both halves are proved against
 * real drivers by {@code StreamingProfileDriverIntegrationTest}.
 */
public final class StreamingProfiles {

    /**
     * The fetch size for every dialect that does not need a driver-specific signal. Also what
     * {@code SqlStatement} falls back to when a driver cannot be given MySQL's sentinel.
     */
    public static final int CONSERVATIVE_FETCH_SIZE = 1000;

    private static final StreamingProfile DEFAULT = new StreamingProfile(CONSERVATIVE_FETCH_SIZE,
            false);

    private StreamingProfiles() {
    }

    /** The streaming profile for a dialect id, or the default when unknown. */
    public static StreamingProfile forDialect(String dialect) {
        if (dialect == null) {
            return DEFAULT;
        }
        return switch (dialect.toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> new StreamingProfile(1000, true);
            case "mysql" -> new StreamingProfile(Integer.MIN_VALUE, false);
            // MariaDB speaks MySQL's protocol but its driver does not take MySQL's row-streaming
            // signal: MariaDB Connector/J answers setFetchSize(Integer.MIN_VALUE) with "invalid
            // fetch size". A positive fetch size opens its cursor, and unlike Connector/J it
            // leaves the connection usable while the result set is open. Reached only when an
            // operator declares `dialect: mariadb`, or through the vendor a transfer detects from
            // the connection — a jdbc:mariadb:// URL infers `mysql`, which is why this branch is
            // not what saves MariaDB. SqlStatement asking the driver is.
            case "mariadb" -> new StreamingProfile(CONSERVATIVE_FETCH_SIZE, false);
            case "oracle", "sqlserver" -> new StreamingProfile(1000, false);
            // In-process engine: results stream natively, no cursor-mode autocommit dance.
            case "duckdb" -> new StreamingProfile(1000, false);
            default -> DEFAULT;
        };
    }
}
