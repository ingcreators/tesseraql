package io.tesseraql.core.files;

import io.tesseraql.core.spool.TempStore;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * The one write dispatch of the export pipeline (docs/export-pipeline.md, decision 1): hand the
 * codec the row source its streaming declaration asks for — the cursor itself when it writes
 * rows through, a spooled re-readable set when it holds them, and one document per group when
 * the export splits.
 *
 * <p>This decision existed twice, once in the synchronous producer and once in the
 * asynchronous transfer service, and the campaign history shows the cost: five separate fixes
 * each landed as paired edits to both files. Statement preparation, transactions and delivery
 * genuinely differ per surface and stay with the callers; what a codec is handed does not.
 */
public final class ExportWrite {

    private ExportWrite() {
    }

    /**
     * Writes the export through {@code codec} into {@code out}, enrichment first.
     *
     * <p>The enrichment wraps the iterator rather than any one branch: a streaming codec then
     * sees a sliding window, a buffering one spools what comes out, and a {@code splitBy:}
     * bundle spools it too — none of them learns that an enrichment happened
     * (docs/lookups.md, slice 13b).
     *
     * @param filename the declared download name; only a {@code splitBy:} export reads it, for
     *                 its per-group entries. The row count is the caller's to read off its own
     *                 {@link ResultSetRows} once the write returns — every branch exhausts the
     *                 source.
     */
    public static void write(FileCodec codec, FileWriteSpec spec, TempStore tempStore,
            Iterator<Map<String, Object>> source, RowEnricher enricher, int enrichWindow,
            Map<String, Object> values, String filename, OutputStream out) throws IOException {
        Iterator<Map<String, Object>> rows = EnrichingRows.of(source, enricher, enrichWindow);
        if (spec.splitBy() != null && !spec.splitBy().isBlank()) {
            // One document per group, bundled (docs/export-pipeline.md, decision 12). The rows
            // are spooled whatever the codec declared: splitting is a deliberate choice, and
            // holding one group at a time is what it buys.
            try (SpooledRows spooled = SpooledRows.drain(tempStore, rows)) {
                SplitExport.write(codec, spec, spooled, values, spec.splitBy(), filename, out);
            }
            return;
        }
        if (codec.streams(spec)) {
            codec.write(out, spec, ExportModel.streaming(rows, values));
            return;
        }
        try (SpooledRows spooled = SpooledRows.drain(tempStore, rows)) {
            codec.write(out, spec, ExportModel.repeatable(spooled, values));
        }
    }

    /**
     * The cap an export runs under: the declared one, but only where the codec holds the rows
     * for this spec (docs/export-pipeline.md, decisions 6 and 7). A streaming export
     * accumulates nothing, so a ceiling there would exist only to be raised.
     */
    public static ExportRowCap effectiveCap(FileCodec codec, FileWriteSpec spec,
            ExportRowCap declared) {
        return codec.streams(spec) || declared == null
                ? ExportRowCap.unbounded()
                : declared;
    }

    /**
     * One named result: the rows drained to a spool under the export's own ceiling, shaped as
     * every result is — {@code rows}, {@code rowCount}, {@code first}. The spool is added to
     * {@code spools}, which the surface owning the write reclaims after the codec is done: the
     * named results outlive the codec's write and nothing else.
     */
    public static Map<String, Object> namedResult(TempStore tempStore,
            Iterator<Map<String, Object>> rows, java.util.List<SpooledRows> spools) {
        SpooledRows spooled = SpooledRows.drain(tempStore, rows);
        spools.add(spooled);
        return ExportModel.result(spooled, spooled.size());
    }
}
