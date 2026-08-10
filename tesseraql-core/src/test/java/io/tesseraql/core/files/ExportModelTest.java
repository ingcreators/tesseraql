package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The row source a codec is handed follows its own streaming declaration
 * (docs/export-pipeline.md, decision 1), and asking for the other one fails rather than being
 * described in prose: a streaming codec cannot be given a second pass it would have to buffer to
 * provide, and a buffering codec asking for a one-shot iterator has thrown away the very ability
 * the spool bought it.
 */
class ExportModelTest {

    private static final Map<String, Object> ROW = Map.of("name", "alpha");

    @Test
    void aStreamingExportHandsOverItsRowsOnce() {
        ExportModel model = ExportModel.streaming(List.of(ROW).iterator(), Map.of());

        assertThat(model.rows()).toIterable().containsExactly(ROW);
        assertThatThrownBy(model::repeatableRows)
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("rows() once")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2856");
    }

    @Test
    void aBufferingExportHandsOverARowSetThatCanBeWalkedAgain() {
        ExportModel model = ExportModel.repeatable(List.of(ROW), Map.of());

        assertThat(model.repeatableRows()).containsExactly(ROW);
        assertThat(model.repeatableRows()).containsExactly(ROW);
        assertThatThrownBy(model::rows)
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("repeatableRows()")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2856");
    }

    @Test
    void namedValuesTravelBesideTheRows() {
        ExportModel model = ExportModel.repeatable(List.of(ROW), Map.of("header", "x"))
                .with("totals", 3);

        assertThat(model.values()).containsEntry("header", "x").containsEntry("totals", 3);
        // The rows are untouched by adding a value.
        assertThat(model.repeatableRows()).containsExactly(ROW);
    }
}
