package io.tesseraql.yaml.config;

import io.tesseraql.core.sql.SqlStatement;

/**
 * The one place the app-wide SQL bounds become values (docs/contract-sql-execution.md structural
 * decision 3 for the timeout, docs/export-pipeline.md decision 7 for the row cap). Each key was
 * read in several places with several different default expressions; a bound resolved differently
 * depending on which executor asked is a bound only by coincidence — and for the row cap it was
 * worse than that, since one executor did not read the key at all.
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

    /**
     * The app-wide row ceiling for a materializing read:
     * {@code tesseraql.resultMaterialization.maxRows}, else the same 10,000 the primitive
     * defaults to. Deliberately unclamped, unlike the timeout — a negative value is the live
     * "no cap" sentinel that every consumer honours and that {@code export.maxRows:} documents
     * as its opt-out. Per-binding declarations override this where they exist.
     */
    public static int maxRows(AppConfig config) {
        return config.getString("tesseraql.resultMaterialization.maxRows")
                .map(Integer::parseInt)
                .orElse(SqlStatement.DEFAULT_MAX_ROWS);
    }

    /** The app-wide overflow policy: {@code tesseraql.resultMaterialization.onOverflow}. */
    public static String onOverflow(AppConfig config) {
        return config.getString("tesseraql.resultMaterialization.onOverflow")
                .orElse(SqlStatement.DEFAULT_ON_OVERFLOW);
    }
}
