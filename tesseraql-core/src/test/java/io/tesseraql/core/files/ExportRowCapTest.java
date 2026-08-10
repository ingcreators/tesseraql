package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import org.junit.jupiter.api.Test;

/**
 * The ceiling follows the buffering rather than the path (docs/export-pipeline.md, decision 7):
 * a codec that holds every row before it writes is exactly as exposed as a materializing query,
 * and one that writes rows through as they arrive has nothing to cap.
 */
class ExportRowCapTest {

    @Test
    void anUnboundedCapAdmitsEverything() {
        ExportRowCap cap = ExportRowCap.unbounded();

        assertThat(cap.admits(0)).isTrue();
        assertThat(cap.admits(1_000_000)).isTrue();
    }

    @Test
    void failModeRaisesAtTheCeilingAndNamesTheRemedy() {
        ExportRowCap cap = new ExportRowCap(2, "fail", "pdf");

        assertThat(cap.admits(0)).isTrue();
        assertThat(cap.admits(1)).isTrue();
        assertThatThrownBy(() -> cap.admits(2))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("maxRows=2")
                .hasMessageContaining("pdf")
                .hasMessageContaining("splitBy:")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2850");
    }

    @Test
    void warnModeTruncatesInsteadOfFailing() {
        ExportRowCap cap = new ExportRowCap(2, "warn", "excel");

        assertThat(cap.admits(1)).isTrue();
        assertThat(cap.admits(2)).isFalse();
    }
}
