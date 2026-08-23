package io.tesseraql.yaml.config;

import io.tesseraql.core.sql.SqlStatement;

/**
 * The one place {@code tesseraql.sql.timeoutSeconds} becomes a value
 * (docs/contract-sql-execution.md structural decision 3). The key was read in six places with
 * three different default expressions; a bound resolved differently depending on which executor
 * asked is a bound only by coincidence.
 */
public final class SqlDefaults {

    private SqlDefaults() {
    }

    /**
     * The app-wide statement bound in seconds: {@code tesseraql.sql.timeoutSeconds}, else the
     * same 30 the primitive itself defaults to; an explicit {@code 0} opts out. Per-binding
     * {@code timeoutSeconds:} overrides stay with the binding that declares them.
     */
    public static int timeoutSeconds(AppConfig config) {
        return config.getString("tesseraql.sql.timeoutSeconds")
                .map(Integer::parseInt)
                .orElse(SqlStatement.DEFAULT_TIMEOUT_SECONDS);
    }
}
