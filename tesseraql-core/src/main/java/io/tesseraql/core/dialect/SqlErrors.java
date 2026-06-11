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
        if (!sqlState.startsWith("23")) {
            return SqlErrorKind.UNKNOWN;
        }
        return switch (sqlState) {
            case "23505" -> SqlErrorKind.UNIQUE_VIOLATION;
            case "23503" -> SqlErrorKind.FOREIGN_KEY_VIOLATION;
            case "23502" -> SqlErrorKind.NOT_NULL_VIOLATION;
            case "23514" -> SqlErrorKind.CHECK_VIOLATION;
            default -> byVendorCode(vendorCode); // generic class 23 (e.g. MySQL/Oracle 23000)
        };
    }

    /** True when the exception is a unique/primary-key conflict on any supported dialect. */
    public static boolean isUniqueViolation(SQLException ex) {
        return classify(ex) == SqlErrorKind.UNIQUE_VIOLATION;
    }

    private static SqlErrorKind byVendorCode(int vendorCode) {
        return switch (vendorCode) {
            case 1, 1062, 1586 -> SqlErrorKind.UNIQUE_VIOLATION;          // Oracle ORA-00001; MySQL dup
            case 1216, 1217, 1451, 1452, 2291, 2292 -> SqlErrorKind.FOREIGN_KEY_VIOLATION;
            case 1048, 1364, 1400 -> SqlErrorKind.NOT_NULL_VIOLATION;     // MySQL; Oracle ORA-01400
            case 3819, 4025, 2290 -> SqlErrorKind.CHECK_VIOLATION;        // MySQL; Oracle ORA-02290
            default -> SqlErrorKind.INTEGRITY_CONSTRAINT;
        };
    }
}
