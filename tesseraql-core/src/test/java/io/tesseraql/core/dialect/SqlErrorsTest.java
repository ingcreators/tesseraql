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

    @Test
    void isUniqueViolationFromException() {
        assertThat(SqlErrors.isUniqueViolation(new SQLException("dup", "23505"))).isTrue();
        assertThat(SqlErrors.isUniqueViolation(new SQLException("dup", "23000", 1062))).isTrue();
        assertThat(SqlErrors.isUniqueViolation(new SQLException("fk", "23503"))).isFalse();
        assertThat(SqlErrors.isUniqueViolation(null)).isFalse();
    }
}
