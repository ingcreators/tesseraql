package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.dialect.SqlErrorKind;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The sentence a rejected import row publishes (docs/csv-import.md decision 4): the failure
 * class, never the driver's text.
 */
class RowFailuresTest {

    /** The duplicate key pgjdbc reports, message and all. */
    private static SQLException duplicateKey() {
        return new SQLException(
                "ERROR: duplicate key value violates unique constraint \"items_pkey\"\n"
                        + "  Detail: Key (name)=(zeta) already exists.",
                "23505");
    }

    @Test
    void aDatabaseRefusalSpeaksItsClass() {
        assertThat(RowFailures.message(duplicateKey()))
                .isEqualTo("A record with these values already exists.");
        assertThat(RowFailures.message(new SQLException("null value in column", "23502")))
                .isEqualTo("A value this row must supply is missing.");
        assertThat(RowFailures.message(new SQLException("deadlock detected", "40P01")))
                .isEqualTo("The row could not be written because another change held it.");
    }

    @Test
    void aWrappedDatabaseRefusalIsStillFoundInTheChain() {
        // Pools and dialect layers wrap; the class is the driver's whatever wraps it.
        RuntimeException wrapped = new IllegalStateException("row 2 failed",
                new java.io.UncheckedIOException(new java.io.IOException("x", duplicateKey())));

        assertThat(RowFailures.message(wrapped))
                .isEqualTo("A record with these values already exists.");
    }

    @Test
    void anythingThatIsNotADatabaseErrorGetsTheGenericSentence() {
        // The same leak argument applies to any message this code did not write, so a
        // non-SQL failure does not get to publish its own text either.
        assertThat(RowFailures.message(new IllegalStateException("update items set qty = 3")))
                .isEqualTo("The row could not be written.");
    }

    @Test
    void theDriverTextIsKeptWholeAndAbsentWhenThereIsNone() {
        assertThat(RowFailures.detail(duplicateKey())).contains("items_pkey").contains("zeta");
        // Null rather than "null": an absent detail must be absent on the wire.
        assertThat(RowFailures.detail(new SQLException((String) null, "23505"))).isNull();
        assertThat(RowFailures.detail(new SQLException("   ", "23505"))).isNull();
        assertThat(RowFailures.detail(null)).isNull();
    }

    @Test
    void everyClassHasItsOwnSentence() {
        // A new SqlErrorKind must not silently share another's wording — the report's whole
        // value is that the class it names is the class that happened.
        assertThat(Arrays.stream(SqlErrorKind.values()).map(RowFailures::message)
                .collect(Collectors.toSet()))
                .hasSize(SqlErrorKind.values().length);
        assertThat(Arrays.stream(SqlErrorKind.values()).map(RowFailures::message))
                .allSatisfy(sentence -> assertThat(sentence).isNotBlank().endsWith("."));
    }
}
