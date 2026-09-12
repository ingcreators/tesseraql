package io.tesseraql.pipeline.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bundle a split export downloads as is named for the declared filename's stem: the
 * placeholder goes, the extension goes, and only then the separator the placeholder left
 * behind — a strip that used to run before the extension was cut never found it, so every
 * split export downloaded as {@code orders-.zip} (docs/download-name-and-bytes.md).
 */
class SqlStepZipNameTest {

    @Test
    void theBundleIsNamedForTheStemWithoutTheSeparatorThePlaceholderLeaves() {
        for (Map.Entry<String, String> row : Map.of(
                "orders-{key}.csv", "orders.zip",
                "daily-orders-{key}.csv", "daily-orders.zip",
                "orders_{key}.csv", "orders.zip",
                "orders.{key}.csv", "orders.zip",
                "受注-{key}.csv", "受注.zip",
                "orders-{key}", "orders.zip").entrySet()) {
            assertThat(SqlStep.zipName(row.getKey())).as(row.getKey()).isEqualTo(row.getValue());
        }
    }

    @Test
    void aPlaceholderOnlyFilenameBundlesAsExport() {
        assertThat(SqlStep.zipName("{key}.csv")).isEqualTo("export.zip");
        assertThat(SqlStep.zipName("-{key}")).isEqualTo("export.zip");
        assertThat(SqlStep.zipName(".{key}.csv")).isEqualTo("export.zip");
    }
}
