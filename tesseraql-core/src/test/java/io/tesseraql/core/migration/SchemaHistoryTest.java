package io.tesseraql.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The history table name, and the refusal that stops a database from truncating it.
 *
 * <p>Truncation is the failure worth testing for, because the database performs it silently.
 * Measured against PostgreSQL 16 while designing this: {@code create table} with a 109-byte name
 * succeeded and stored 61 bytes, so two applications whose names share a long prefix would record
 * into one history table, each reading the other's applied versions and {@code validate} reporting
 * success over the mixture.
 */
class SchemaHistoryTest {

    /** 49 characters, 109 bytes — the shape that defeats a character count. */
    private static final String LONG_JAPANESE_NAME = "受注管理システム発注モジュール明細行拡張予備領域項目群管理表";

    @Test
    void namesAreIdentifierSafeAndKeepUnicodeLetters() {
        assertThat(SchemaHistory.table("helpdesk-app"))
                .isEqualTo("tql_schema_history_helpdesk_app");
        // An ASCII-only class mapped every Japanese name to underscores, so two apps collided.
        assertThat(SchemaHistory.table("受注管理")).isEqualTo("tql_schema_history_受注管理");
    }

    @Test
    void aNamedDatasourceGetsItsOwnTable() {
        assertThat(SchemaHistory.table("helpdesk", "main"))
                .isEqualTo("tql_schema_history_helpdesk");
        assertThat(SchemaHistory.table("helpdesk", "reporting"))
                .isEqualTo("tql_schema_history_helpdesk__reporting");
    }

    /**
     * The check is bytes, not characters. PostgreSQL reports 63 and enforces 63 <em>bytes</em>, so
     * a name that passes a character count is still cut in half.
     */
    @Test
    void anOverlongNameIsRefusedRatherThanTruncated() {
        String table = SchemaHistory.table(LONG_JAPANESE_NAME);
        assertThat(table.length()).as("a character count would let this through").isLessThan(63);
        assertThat(table.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(63);

        assertThatThrownBy(() -> SchemaHistory.requireFits(table, 63))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("bytes")
                .hasMessageContaining("tesseraql.migrations.historyName");
    }

    @Test
    void aNameThatFitsIsAccepted() {
        assertThatCode(() -> SchemaHistory.requireFits(SchemaHistory.table("helpdesk"), 63))
                .doesNotThrowAnyException();
    }

    /** A driver that reports no limit is not second-guessed. */
    @Test
    void noReportedLimitMeansNoCheck() {
        assertThatCode(() -> SchemaHistory.requireFits(SchemaHistory.table(LONG_JAPANESE_NAME), 0))
                .doesNotThrowAnyException();
    }
}
