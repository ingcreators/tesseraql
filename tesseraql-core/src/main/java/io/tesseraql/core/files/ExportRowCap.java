package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;

/**
 * The row ceiling an export runs under (docs/export-pipeline.md, decision 7). The cap follows the
 * buffering rather than the path: an export through a codec that answers false to
 * {@link FileCodec#streams(FileWriteSpec)} accumulates every row before it writes a byte, which is
 * exactly the exposure a materializing query has, so it is bounded the same way. A streaming
 * export is not capped at all.
 *
 * <p>Distinct from {@code TQL-LD-0001}, the materializing-query overflow, because the cause and
 * the remedies differ: this one names the format and mode that buffer, and points at a streaming
 * format rather than at pagination.
 *
 * @param maxRows    the ceiling; negative disables it
 * @param onOverflow {@code fail} (default) or {@code warn}, which truncates instead
 * @param format     the export format, named in the failure so the remedy is obvious
 */
public record ExportRowCap(int maxRows, String onOverflow, String format) {

    /** TQL-LD-2850: a buffering export exceeded its row cap. */
    public static final TqlErrorCode OVERFLOW = new TqlErrorCode(TqlDomain.LD, 2850);

    /** No ceiling — a streaming codec, or an export that opted out with a negative maxRows. */
    public static ExportRowCap unbounded() {
        return new ExportRowCap(-1, "fail", null);
    }

    public boolean truncates() {
        return "warn".equals(onOverflow);
    }

    /**
     * Whether the row at {@code alreadyWritten} may still be written. Returns false only in
     * {@code warn} mode, where the export truncates; in {@code fail} mode it throws.
     */
    public boolean admits(long alreadyWritten) {
        if (maxRows < 0 || alreadyWritten < maxRows) {
            return true;
        }
        if (truncates()) {
            return false;
        }
        throw new TqlException(OVERFLOW, "Export exceeds maxRows=" + maxRows
                + ": the " + (format == null ? "declared" : format)
                + " export holds every row before it writes (use a streaming format such as csv,"
                + " split the document with splitBy:, or raise export.maxRows)");
    }
}
