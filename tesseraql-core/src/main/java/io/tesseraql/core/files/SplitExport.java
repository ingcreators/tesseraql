package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One document per group, delivered as a single ZIP (docs/export-pipeline.md, decision 12).
 *
 * <p>This is what a large printable document does instead of streaming. Splitting a single logical
 * table into chunks and merging them back would break four things the current output gets right —
 * page numbers restart, a chunk's last page is partly empty, fonts are embedded once per chunk, and
 * a footer total cannot be computed from a chunk. Splitting by <em>meaning</em> breaks none of
 * them, because the boundary is one the reader already believes in: page numbers are per invoice, a
 * partly-empty last page is the end of a document, fonts are embedded once per document because
 * each document is a document, and a footer total is that group's total.
 *
 * <p>The bundle is a ZIP so that one file still leaves the export: the spool, the transfer record,
 * the download endpoint, the push destinations and the mail attachment all keep working untouched.
 * One group still produces a ZIP and zero rows produce an empty one — the output shape is a
 * property of the route, not of today's data.
 */
public final class SplitExport {

    /** TQL-LD-2857: two group keys name the same file once made safe for a filesystem. */
    static final TqlErrorCode FILENAME_COLLISION = new TqlErrorCode(TqlDomain.LD, 2857);
    /** TQL-LD-2858: a split export's filename carries no {@code {key}} placeholder. */
    public static final TqlErrorCode NO_KEY_PLACEHOLDER = new TqlErrorCode(TqlDomain.LD, 2858);

    /** The one placeholder a split filename may carry. */
    public static final String KEY = "{key}";

    private SplitExport() {
    }

    /**
     * Writes one document per group into {@code out} as a ZIP, holding one group at a time.
     *
     * @param filename the download name of each entry, carrying {@link #KEY}
     */
    public static long write(FileCodec codec, FileWriteSpec spec, SpooledRows rows,
            Map<String, Object> values, String splitBy, String filename, OutputStream out)
            throws IOException {
        if (filename == null || !filename.contains(KEY)) {
            throw new TqlException(NO_KEY_PLACEHOLDER, "splitBy: writes one document per group, so"
                    + " filename: must carry " + KEY + " - otherwise every group would be written"
                    + " to the same name and only the last would survive");
        }
        ExportGroups groups = ExportGroups.of(rows, splitBy);
        Map<String, Map<Object, Object>> perDocument = perDocumentValues(values, splitBy);
        Map<String, Object> entries = new LinkedHashMap<>();
        long written = 0;
        // finish() rather than close(): the caller owns the stream it handed over.
        ZipOutputStream zip = new ZipOutputStream(out);
        for (ExportGroups.Group group : groups) {
            String entry = filename.replace(KEY, safe(group.key()));
            Object previous = entries.putIfAbsent(entry, group.key());
            if (previous != null) {
                throw new TqlException(FILENAME_COLLISION, "Groups '" + previous + "' and '"
                        + group.key() + "' both name '" + entry + "' once made safe for a"
                        + " filesystem - one document would overwrite the other");
            }
            zip.putNextEntry(new ZipEntry(entry));
            Map<String, Object> documentValues = narrow(values, perDocument, group.key());
            // Each document is written by the codec exactly as an unsplit one would be, so the
            // model still follows its streaming declaration: the group's rows are re-readable in
            // their own right, and a streaming codec takes them once. Either way it never sees
            // more than one group.
            codec.write(new NonClosing(zip), spec, codec.streams(spec)
                    ? ExportModel.streaming(group.rows().iterator(), documentValues)
                    : ExportModel.repeatable(group.rows(), documentValues));
            zip.closeEntry();
            written++;
        }
        zip.finish();
        return written;
    }

    /**
     * The values one document sees (docs/export-pipeline.md, decision 16): a named result whose
     * rows carry the split column is narrowed to this group's rows, and one that does not is
     * shared whole.
     *
     * <p>That rule reads from what the query selected, so an author states the relationship by
     * selecting the column rather than by declaring anything: a customer query that selects
     * {@code customer_id} belongs to its invoice, and a company query that does not belongs to
     * all of them. Five hundred invoices cost one customer query, not five hundred — the same
     * ordered grouping the extraction already uses does the narrowing.
     */
    static Map<String, Object> narrow(Map<String, Object> values,
            Map<String, Map<Object, Object>> perDocument, Object key) {
        if (perDocument.isEmpty()) {
            return values;
        }
        Map<String, Object> narrowed = new LinkedHashMap<>(values);
        perDocument.forEach((name, byKey) -> narrowed.put(name,
                byKey.getOrDefault(key, ExportModel.result(List.of(), 0))));
        return Map.copyOf(narrowed);
    }

    /**
     * Indexes the narrowable results once, before any document is written: one pass over each,
     * yielding that result's rows per key. A named result that does not carry the split column is
     * absent from the index and is shared whole.
     *
     * <p>The grouping is the extraction's, so a narrowable result inherits its ordering contract —
     * unordered rows fail with the query named, rather than each document silently receiving the
     * first run of its key and none of the rest.
     */
    private static Map<String, Map<Object, Object>> perDocumentValues(Map<String, Object> values,
            String splitBy) {
        Map<String, Map<Object, Object>> perDocument = new LinkedHashMap<>();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            Object rows = value.getValue() instanceof Map<?, ?> result ? result.get("rows") : null;
            if (!(rows instanceof SpooledRows spooled) || !spooled.columns().contains(splitBy)) {
                continue;
            }
            ExportGroups groups;
            try {
                groups = ExportGroups.of(spooled, splitBy);
            } catch (TqlException ex) {
                throw new TqlException(ExportGroups.UNORDERED, "Named query '" + value.getKey()
                        + "' selects " + splitBy + ", so each document reads its own rows from it"
                        + " - order it by " + splitBy + " as the extraction is ordered ("
                        + ex.getMessage() + ")");
            }
            Map<Object, Object> byKey = new LinkedHashMap<>();
            for (ExportGroups.Group group : groups) {
                long count = 0;
                for (Map<String, Object> ignored : group.rows()) {
                    count++;
                }
                byKey.put(group.key(), ExportModel.result(group.rows(), count));
            }
            perDocument.put(value.getKey(), byKey);
        }
        return perDocument;
    }

    /**
     * A group key as a filename component: anything a filesystem or a zip reader would object to
     * becomes an underscore, and the result is bounded. Two keys that collide after this fail
     * rather than overwrite, which is the whole reason {@code {key}} is mandatory.
     */
    static String safe(Object key) {
        String text = String.valueOf(key);
        String cleaned = text.replaceAll("[^\\p{L}\\p{N}._-]", "_").replaceAll("^\\.+", "_");
        if (cleaned.isBlank()) {
            return "_";
        }
        return cleaned.length() > 100
                ? cleaned.substring(0, 100).toLowerCase(Locale.ROOT)
                : cleaned;
    }

    /** A zip entry ends with closeEntry(), not with the codec closing the whole archive. */
    private static final class NonClosing extends OutputStream {

        private final OutputStream delegate;

        NonClosing(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            delegate.write(data, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() {
            // Deliberately not closed: the archive outlives this entry.
        }
    }
}
