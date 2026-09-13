package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bundle a split export downloads as is named for the declared filename's stem: the
 * placeholder goes, the extension goes, and only then the separator the placeholder left
 * behind — a strip that used to run before the extension was cut never found it, so every
 * split export downloaded as {@code orders-.zip} (docs/download-name-and-bytes.md).
 * Moved to core with the method: the bundle name is one derivation for every surface.
 *
 * <p>The placeholder goes WITH its separators wherever it stands (docs/export-hygiene.md P1): a
 * strip that only ran at the end of the stem left {@code {key}.users.csv} as the dot-file
 * {@code .users.zip} — delivered as such into a partner drop — and {@code users-{key}-daily.csv}
 * as {@code users--daily.zip}.
 */
class SplitExportZipNameTest {

    @Test
    void theBundleIsNamedForTheStemWithoutTheSeparatorThePlaceholderLeaves() {
        for (Map.Entry<String, String> row : Map.of(
                "orders-{key}.csv", "orders.zip",
                "daily-orders-{key}.csv", "daily-orders.zip",
                "orders_{key}.csv", "orders.zip",
                "orders.{key}.csv", "orders.zip",
                "受注-{key}.csv", "受注.zip",
                "orders-{key}", "orders.zip").entrySet()) {
            assertThat(SplitExport.zipName(row.getKey())).as(row.getKey())
                    .isEqualTo(row.getValue());
        }
    }

    /** The placeholder and the separator run around it collapse wherever the placeholder stands. */
    @Test
    void thePlaceholderCollapsesWithItsSeparatorsAtAnyPosition() {
        for (Map.Entry<String, String> row : Map.of(
                "{key}-users.csv", "users.zip",
                "{key}_users.csv", "users.zip",
                "{key}.users.csv", "users.zip",
                "users-{key}-daily.csv", "users-daily.zip",
                "users-{key}.daily.csv", "users-daily.zip",
                "report.{key}.2026-09.csv", "report.2026-09.zip",
                "report.2026-09.{key}.csv", "report.2026-09.zip",
                "users-{key}.tar.gz", "users-tar.zip",
                ".hidden-{key}.csv", ".hidden.zip").entrySet()) {
            assertThat(SplitExport.zipName(row.getKey())).as(row.getKey())
                    .isEqualTo(row.getValue());
        }
    }

    @Test
    void aPlaceholderOnlyFilenameBundlesAsExport() {
        assertThat(SplitExport.zipName("{key}.csv")).isEqualTo("export.zip");
        assertThat(SplitExport.zipName("-{key}")).isEqualTo("export.zip");
        assertThat(SplitExport.zipName(".{key}.csv")).isEqualTo("export.zip");
    }
}
