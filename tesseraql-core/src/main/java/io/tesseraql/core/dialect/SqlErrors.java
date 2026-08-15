package io.tesseraql.core.dialect;

import java.sql.SQLException;

/**
 * Classifies {@link SQLException}s into a portable {@link SqlErrorKind} across dialects (design
 * ch. 42 SQLState mapping). It prefers the SQL-standard 5-character SQLState (as PostgreSQL emits),
 * then falls back to the integrity-constraint class with the driver's vendor code (as MySQL and
 * Oracle emit the generic {@code 23000}). This centralizes constraint detection so callers no longer
 * hard-code SQLState prefixes.
 */
public final class SqlErrors {

    private SqlErrors() {
    }

    /** Classifies a SQL exception. */
    public static SqlErrorKind classify(SQLException ex) {
        return ex == null ? SqlErrorKind.UNKNOWN : classify(ex.getSQLState(), ex.getErrorCode());
    }

    /** Classifies by SQLState and vendor (driver-specific) code. */
    public static SqlErrorKind classify(String sqlState, int vendorCode) {
        if (sqlState == null) {
            return SqlErrorKind.UNKNOWN;
        }
        if (sqlState.startsWith("40")) {
            return SqlErrorKind.SERIALIZATION_FAILURE; // 40001 serialization, 40P01 deadlock
        }
        if (sqlState.startsWith("23")) {
            return switch (sqlState) {
                case "23505" -> SqlErrorKind.UNIQUE_VIOLATION;
                case "23503" -> SqlErrorKind.FOREIGN_KEY_VIOLATION;
                case "23502" -> SqlErrorKind.NOT_NULL_VIOLATION;
                case "23514" -> SqlErrorKind.CHECK_VIOLATION;
                // Generic class 23 (MySQL/Oracle/SQL Server 23000): the class already says this is
                // an integrity failure, so an unrecognized vendor code stays one.
                default -> byVendorCode(vendorCode, SqlErrorKind.INTEGRITY_CONSTRAINT);
            };
        }
        // An inconclusive SQLState is not a verdict, and treating it as one lost real
        // classifications (docs/audit-hardening.md Decision 3). Under mssql-jdbc's
        // xopenStates=true the same driver reports 42000 for a duplicate key and for a deadlock,
        // so discarding everything outside class 23 before reading the vendor code threw away the
        // answer the driver had already given.
        //
        // The default here must be UNKNOWN and not INTEGRITY_CONSTRAINT: pgjdbc sets vendor code 0
        // on every SQLException, so a PostgreSQL syntax error, permission denial or connection
        // failure would otherwise arrive on this path and classify as an integrity constraint.
        return byVendorCode(vendorCode, SqlErrorKind.UNKNOWN);
    }

    /** True when the exception is a unique/primary-key conflict on any supported dialect. */
    public static boolean isUniqueViolation(SQLException ex) {
        return classify(ex) == SqlErrorKind.UNIQUE_VIOLATION;
    }

    /**
     * Classifies by the driver's own error number, falling back to {@code fallback}.
     *
     * <p>The fallback is a parameter rather than a constant because this is reached from two places
     * that know different amounts. From a generic class-23 SQLState the failure is already known to
     * be an integrity violation; from an inconclusive SQLState nothing is known, and guessing
     * integrity there would misclassify every unrecognized error on drivers that report a vendor
     * code of zero.
     */
    private static SqlErrorKind byVendorCode(int vendorCode, SqlErrorKind fallback) {
        return switch (vendorCode) {
            // Oracle ORA-00001; MySQL dup; SQL Server 2601 (unique index) and 2627 (PK/unique
            // constraint) — the pair mssql-jdbc reports for a duplicate key, and the reason
            // isUniqueViolation could not return true on SQL Server at all.
            case 1, 1062, 1586, 2601, 2627 -> SqlErrorKind.UNIQUE_VIOLATION;
            // SQL Server 547 is "conflicted with the ... constraint" for FOREIGN KEY and CHECK
            // alike — one number, two meanings, distinguished only in the message text. Foreign
            // key is the reading that matters to callers here; a check violation classifying as a
            // constraint failure of the wrong flavour is a worse answer than the generic one it
            // used to get, but not a wrong one.
            case 1216, 1217, 1451, 1452, 2291, 2292, 547 -> SqlErrorKind.FOREIGN_KEY_VIOLATION;
            // MySQL; Oracle ORA-01400; SQL Server 515.
            case 1048, 1364, 1400, 515 -> SqlErrorKind.NOT_NULL_VIOLATION;
            case 3819, 4025, 2290 -> SqlErrorKind.CHECK_VIOLATION; // MySQL; Oracle ORA-02290
            // SQL Server 1205 is the deadlock victim, MySQL 1205 the lock-wait timeout; both are
            // the retryable shape. SQL Server reports it under SQLState 40001 normally and 42000
            // under xopenStates, so without the inconclusive-state path above it was lost too.
            case 1205 -> SqlErrorKind.SERIALIZATION_FAILURE;
            default -> fallback;
        };
    }
}
