package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.OrderedCopies;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** What a split transfer records as its format: the bundle's, not its documents'. */
    public static final String BUNDLE_FORMAT = "zip";

    /** The content type every split bundle is served under, whatever codec wrote the entries. */
    public static final String BUNDLE_CONTENT_TYPE = "application/zip";

    /**
     * The modification time every entry is stamped with: the ZIP format's own epoch. Stamping
     * through {@link ZipEntry#setLastModifiedTime} makes the JDK write the extended-timestamp
     * extra field into the local header and the central directory, and that field is what
     * Info-ZIP {@code unzip} 6.00 (stock Debian and Ubuntu) needs before it honours the UTF-8 name
     * flag — an entry with no extra field at all has its name converted as if it were OEM-encoded,
     * so a Japanese group key unpacked as garbage. A fixed time also makes two identical exports
     * byte-identical; the wall clock used to be the one thing that differed between them.
     * {@code setTime(long)} with any time in the DOS range writes no such field.
     */
    static final FileTime ENTRY_TIME = FileTime.from(Instant.parse("1980-01-01T00:00:00Z"));

    /** The bound on a group key as a filename component, in UTF-16 units. */
    private static final int KEY_BOUND = 100;

    /** The placeholder together with the run of separators on either side of it. */
    private static final Pattern KEY_WITH_SEPARATORS = Pattern.compile("[-_.]*\\{key}[-_.]*");

    private SplitExport() {
    }

    /**
     * The bundle's own name: the declared filename's stem with the placeholder dropped together
     * with the separators around it — {@code orders-{key}.csv} bundles as {@code orders.zip},
     * {@code {key}.users.csv} as {@code users.zip}, {@code users-{key}-daily.csv} as
     * {@code users-daily.zip}, and a placeholder-only {@code {key}.csv} as {@code export.zip}.
     *
     * <p>Wherever the placeholder stands, it goes with its separator run: at either end of the
     * stem the run goes entirely, in the middle it collapses to its first character. A strip that
     * ran at the end of the stem only left a leading placeholder's separator behind, so
     * {@code {key}.users.csv} bundled as the dot-file {@code .users.zip}.
     */
    public static String zipName(String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot >= 0 ? filename.substring(0, dot) : filename;
        Matcher key = KEY_WITH_SEPARATORS.matcher(stem);
        StringBuilder collapsed = new StringBuilder();
        while (key.find()) {
            boolean atAnEnd = key.start() == 0 || key.end() == stem.length();
            String run = key.group();
            String separator = atAnEnd
                    ? ""
                    : run.startsWith(KEY)
                            ? run.substring(KEY.length(), KEY.length() + 1)
                            : run.substring(0, 1);
            key.appendReplacement(collapsed, Matcher.quoteReplacement(separator));
        }
        key.appendTail(collapsed);
        String name = collapsed.toString().replaceAll("[-_.]+$", "");
        return (name.isBlank() ? "export" : name) + ".zip";
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
            ZipEntry zipEntry = new ZipEntry(entry);
            zipEntry.setLastModifiedTime(ENTRY_TIME);
            zip.putNextEntry(zipEntry);
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
        return OrderedCopies.map(narrowed);
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
                // One walk answers both the count and the first row: opening a second reader
                // just to peek at row one would abandon it mid-spool, and an abandoned reader
                // keeps its stream (and its staging copy) until a walk that never comes.
                long count = 0;
                Map<String, Object> first = null;
                for (Map<String, Object> row : group.rows()) {
                    if (count == 0) {
                        first = row;
                    }
                    count++;
                }
                byKey.put(group.key(), ExportModel.result(group.rows(), count, first));
            }
            perDocument.put(value.getKey(), byKey);
        }
        return perDocument;
    }

    /**
     * A group key as a filename component: anything a filesystem or a zip reader would object to
     * becomes an underscore, and the result is bounded. Two keys that collide after this fail
     * rather than overwrite, which is the whole reason {@code {key}} is mandatory.
     *
     * <p>The bound cuts on a code-point boundary: a cut through a surrogate pair leaves a lone
     * surrogate the ZIP name encoder refuses, and the export failed after the query ran. The
     * key's case is kept — the over-length branch alone used to lower-case, so two long keys
     * differing only in case collided while the same short keys did not.
     */
    static String safe(Object key) {
        String text = String.valueOf(key);
        String cleaned = text.replaceAll("[^\\p{L}\\p{N}._-]", "_").replaceAll("^\\.+", "_");
        if (cleaned.isBlank()) {
            return "_";
        }
        if (cleaned.length() <= KEY_BOUND) {
            return cleaned;
        }
        int end = Character.isHighSurrogate(cleaned.charAt(KEY_BOUND - 1))
                && Character.isLowSurrogate(cleaned.charAt(KEY_BOUND))
                        ? KEY_BOUND - 1
                        : KEY_BOUND;
        return cleaned.substring(0, end);
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
