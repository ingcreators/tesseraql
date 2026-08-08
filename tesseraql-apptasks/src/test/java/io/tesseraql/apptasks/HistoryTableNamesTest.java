package io.tesseraql.apptasks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Per-app Flyway history table names keep Unicode letters (docs/unicode-identifiers.md) —
 * the ASCII-only sanitizer mapped every Japanese app name to underscores, so two Japanese
 * apps silently shared one history table.
 */
class HistoryTableNamesTest {

    @Test
    void japaneseAppNamesKeepDistinctHistoryTables() {
        assertThat(AppMigrator.historyTable("受注管理"))
                .isEqualTo("tql_schema_history_受注管理");
        assertThat(AppMigrator.historyTable("受注管理"))
                .isNotEqualTo(AppMigrator.historyTable("顧客管理"));
    }

    @Test
    void asciiNamesSanitizeAsBefore() {
        assertThat(AppMigrator.historyTable("My-App")).isEqualTo("tql_schema_history_my_app");
    }
}
