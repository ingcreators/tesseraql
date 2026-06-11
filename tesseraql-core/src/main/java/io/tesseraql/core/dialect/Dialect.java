package io.tesseraql.core.dialect;

import java.util.Optional;

/**
 * A supported SQL dialect and its capability metadata (design ch. 42.2). Used for dialect-specific
 * SQL file resolution and, in later phases, pagination helpers and SQLState mapping.
 */
public enum Dialect {

    POSTGRES("postgres",
            new DialectCapabilities(true, true, "on-conflict", "\"", "columns")), MYSQL("mysql",
                    new DialectCapabilities(true, false, "on-duplicate-key", "`", "auto")), ORACLE(
                            "oracle",
                            new DialectCapabilities(false, true, "merge", "\"",
                                    "columns")), SQLSERVER("sqlserver",
                                            new DialectCapabilities(false, true, "merge", "\"",
                                                    "auto"));

    private final String id;
    private final DialectCapabilities capabilities;

    Dialect(String id, DialectCapabilities capabilities) {
        this.id = id;
        this.capabilities = capabilities;
    }

    public String id() {
        return id;
    }

    public DialectCapabilities capabilities() {
        return capabilities;
    }

    /** Resolves a dialect by id (case-insensitive). */
    public static Optional<Dialect> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (Dialect dialect : values()) {
            if (dialect.id.equalsIgnoreCase(id)) {
                return Optional.of(dialect);
            }
        }
        return Optional.empty();
    }

    /** Infers the dialect from a JDBC URL, e.g. {@code jdbc:postgresql://...} -&gt; POSTGRES. */
    public static Optional<Dialect> fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        String url = jdbcUrl.toLowerCase();
        if (url.startsWith("jdbc:postgresql")) {
            return Optional.of(POSTGRES);
        }
        if (url.startsWith("jdbc:mysql") || url.startsWith("jdbc:mariadb")) {
            return Optional.of(MYSQL);
        }
        if (url.startsWith("jdbc:oracle")) {
            return Optional.of(ORACLE);
        }
        if (url.startsWith("jdbc:sqlserver")) {
            return Optional.of(SQLSERVER);
        }
        return Optional.empty();
    }
}
