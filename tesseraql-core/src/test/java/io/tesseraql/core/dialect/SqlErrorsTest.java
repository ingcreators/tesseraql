package io.tesseraql.core.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlErrorsTest {

    @Test
    void classifiesStandardPostgresSqlStates() {
        assertThat(SqlErrors.classify("23505", 0)).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
        assertThat(SqlErrors.classify("23503", 0)).isEqualTo(SqlErrorKind.FOREIGN_KEY_VIOLATION);
        assertThat(SqlErrors.classify("23502", 0)).isEqualTo(SqlErrorKind.NOT_NULL_VIOLATION);
        assertThat(SqlErrors.classify("23514", 0)).isEqualTo(SqlErrorKind.CHECK_VIOLATION);
    }

    @Test
    void classifiesGenericClass23ByVendorCode() {
        // MySQL emits SQLState 23000 with a driver-specific vendor code.
        assertThat(SqlErrors.classify("23000", 1062)).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
        assertThat(SqlErrors.classify("23000", 1452)).isEqualTo(SqlErrorKind.FOREIGN_KEY_VIOLATION);
        assertThat(SqlErrors.classify("23000", 1048)).isEqualTo(SqlErrorKind.NOT_NULL_VIOLATION);
        // Oracle ORA-00001 (unique) also arrives as 23000.
        assertThat(SqlErrors.classify("23000", 1)).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
        // Unknown vendor code on class 23 -> generic integrity constraint.
        assertThat(SqlErrors.classify("23000", 99999)).isEqualTo(SqlErrorKind.INTEGRITY_CONSTRAINT);
    }

    @Test
    void classifiesSerializationAndUnknown() {
        assertThat(SqlErrors.classify("40001", 0)).isEqualTo(SqlErrorKind.SERIALIZATION_FAILURE);
        assertThat(SqlErrors.classify("40P01", 0)).isEqualTo(SqlErrorKind.SERIALIZATION_FAILURE);
        assertThat(SqlErrors.classify("42601", 0)).isEqualTo(SqlErrorKind.UNKNOWN);
        assertThat(SqlErrors.classify(null, 0)).isEqualTo(SqlErrorKind.UNKNOWN);
    }

    /**
     * SQL Server, which the classifier knew nothing about (docs/audit-hardening.md Decision 3).
     *
     * <p>mssql-jdbc maps 208, 515, 547, 2601 and 2627 to SQLState 23000, so a duplicate key reached
     * the vendor-code fallback and fell through to the generic integrity kind — which meant
     * isUniqueViolation could not return true on SQL Server at all. The blast radius was the whole
     * claim-and-dedup layer: job claiming, SAML and webhook replay, document sequences, the event
     * channel store, SCIM, and the duplicate-create contract, which rendered as 500 instead of 409.
     */
    @Test
    void classifiesSqlServerConstraintFailures() {
        assertThat(SqlErrors.classify("23000", 2627)).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
        assertThat(SqlErrors.classify("23000", 2601)).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
        assertThat(SqlErrors.classify("23000", 515)).isEqualTo(SqlErrorKind.NOT_NULL_VIOLATION);
        assertThat(SqlErrors.classify("23000", 547)).isEqualTo(SqlErrorKind.FOREIGN_KEY_VIOLATION);
        assertThat(SqlErrors.isUniqueViolation(new SQLException("dup", "23000", 2627))).isTrue();
    }

    /**
     * The wider half of the same defect: an inconclusive SQLState is not a verdict.
     *
     * <p>Under {@code xopenStates=true} mssql-jdbc reports 42000 for 2601/2627 and for the deadlock
     * victim 1205, and the classifier discarded anything outside class 23 before the vendor code was
     * ever consulted. Serialization-failure detection was lost the same way.
     */
    @Test
    void consultsTheVendorCodeWhenTheSqlStateIsInconclusive() {
        assertThat(SqlErrors.classify("42000", 2627)).isEqualTo(SqlErrorKind.UNIQUE_VIOLATION);
        assertThat(SqlErrors.classify("42000", 1205))
                .isEqualTo(SqlErrorKind.SERIALIZATION_FAILURE);
        assertThat(SqlErrors.isUniqueViolation(new SQLException("dup", "42000", 2627))).isTrue();
    }

    /**
     * The trap that made opening that path dangerous.
     *
     * <p>pgjdbc sets vendor code 0 on every SQLException, so if the newly reachable path had kept
     * the class-23 fallback, a PostgreSQL syntax error, permission denial or connection failure
     * would classify as an integrity constraint. It defaults to UNKNOWN instead.
     */
    @Test
    void anInconclusiveSqlStateWithNoUsableVendorCodeStaysUnknown() {
        assertThat(SqlErrors.classify("42601", 0)).isEqualTo(SqlErrorKind.UNKNOWN);
        assertThat(SqlErrors.classify("42501", 0)).isEqualTo(SqlErrorKind.UNKNOWN);
        assertThat(SqlErrors.classify("08006", 0)).isEqualTo(SqlErrorKind.UNKNOWN);
        assertThat(SqlErrors.classify("42000", 99999)).isEqualTo(SqlErrorKind.UNKNOWN);
        assertThat(SqlErrors.isUniqueViolation(new SQLException("syntax", "42601"))).isFalse();
    }

    @Test
    void isUniqueViolationFromException() {
        assertThat(SqlErrors.isUniqueViolation(new SQLException("dup", "23505"))).isTrue();
        assertThat(SqlErrors.isUniqueViolation(new SQLException("dup", "23000", 1062))).isTrue();
        assertThat(SqlErrors.isUniqueViolation(new SQLException("fk", "23503"))).isFalse();
        assertThat(SqlErrors.isUniqueViolation(null)).isFalse();
    }
}
