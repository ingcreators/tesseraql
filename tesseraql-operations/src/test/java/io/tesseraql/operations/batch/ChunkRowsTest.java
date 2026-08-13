package io.tesseraql.operations.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.files.SpooledRows;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chunk reader's spool round trip. What matters is not that values survive but that their
 * <em>types</em> do: a {@code query-spool} extract used to round-trip through JSON, so a writer
 * bound {@code 1234.5} where the extract read {@code 1234.50} and a string where it read a
 * timestamp. A SQL extract now rides {@link SpooledRows}' tagged-binary encoding — the export
 * pipeline's, for the export pipeline's reason — while an HTTP acquisition keeps JSONL, faithful
 * there because the data was JSON to begin with.
 */
class ChunkRowsTest {

    @TempDir
    Path dir;

    private TempStore store() {
        return new FileTempStore(dir.resolve("spool"));
    }

    @Test
    void aSqlExtractKeepsItsScaleAndItsTemporalTypeThroughTheSpool() {
        TempStore store = store();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item_key", "a01");
        // Scale is part of a money value: 1234.50 is not 1234.5 on the row the writer binds.
        row.put("amount", new BigDecimal("1234.50"));
        java.sql.Timestamp stamp = java.sql.Timestamp.valueOf("2026-08-13 09:15:30");
        stamp.setNanos(123_456_789);
        row.put("loaded_at", stamp);
        SpoolRef ref = SpooledRows.drain(store, List.of(row).iterator()).ref();

        try (ChunkRows rows = ChunkRows.of(store, ref, new ObjectMapper())) {
            assertThat(rows.next()).isTrue();
            Map<String, Object> read = rows.row();
            // One key per column — no lowercase-duplicate aliases doubling the row.
            assertThat(read.keySet()).containsExactly("item_key", "amount", "loaded_at");
            assertThat(read.get("amount")).isEqualTo(new BigDecimal("1234.50"));
            assertThat(read.get("amount")).hasSameClassAs(row.get("amount"));
            assertThat(read.get("loaded_at")).isEqualTo(stamp);
            assertThat(read.get("loaded_at")).hasSameClassAs(stamp);
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void anHttpAcquisitionStillReadsItsJsonlSpoolAsAuthored() throws Exception {
        TempStore store = store();
        SpoolWriter writer = store.createWriter(SpoolKind.JSONL);
        try (writer) {
            writer.write("{\"item_key\":\"h01\",\"payload\":\"1\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            writer.incrementRows(1);
        }

        try (ChunkRows rows = ChunkRows.of(store, writer.toRef(), new ObjectMapper())) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.row())
                    .containsExactly(Map.entry("item_key", "h01"), Map.entry("payload", "1"));
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void closingTheReaderLeavesTheSpoolReadable() {
        // The spool outlives the step that read it: a rerun with --from-failed-step hands the
        // prior spool to the load step, so releasing the reader must never delete the data.
        TempStore store = store();
        SpoolRef ref = SpooledRows
                .drain(store, List.<Map<String, Object>>of(Map.of("item_key", "a01")).iterator())
                .ref();

        try (ChunkRows first = ChunkRows.of(store, ref, new ObjectMapper())) {
            assertThat(first.next()).isTrue();
        }
        try (ChunkRows again = ChunkRows.of(store, ref, new ObjectMapper())) {
            assertThat(again.next()).isTrue();
            assertThat(again.row()).containsEntry("item_key", "a01");
            assertThat(again.next()).isFalse();
        }
    }
}
