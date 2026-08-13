package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The export's rows seen as ordered groups (docs/export-pipeline.md, decision 3): grouping is the
 * framework's job, not the template's.
 *
 * <p>This exists because the alternative materializes. A jxls report can group with its own
 * {@code groupBy}, but {@code EachCommand.groupIterable} returns a {@code Collection<GroupData>}
 * whose {@code getItems()} is a {@code Collection} — so the one path a template had to
 * {@code multisheet} was the path that buffers every row again, undoing the streaming this
 * campaign buys. A template that iterates {@code groups} and its {@code rows} calls neither.
 *
 * <p><strong>The query must be ordered by the group column.</strong> Boundaries are detected on a
 * pass through the rows, so a key that reappears after its group closed is a query that was not
 * ordered, and it fails ({@code TQL-LD-2851}) rather than quietly writing one group as two.
 *
 * <p>Each group re-reads the spool and yields only its own rows, so peak memory is one row rather
 * than one group — the cost is a scan per group, which suits the shape this serves (a document
 * grouped by department, customer or branch). {@code splitBy} never revisits a group, so it
 * streams in a single pass instead.
 */
public final class ExportGroups implements Iterable<ExportGroups.Group> {

    /** TQL-LD-2851: a group key reappeared after its group closed — the query is not ordered. */
    public static final TqlErrorCode UNORDERED = new TqlErrorCode(TqlDomain.LD, 2851);

    private final SpooledRows rows;
    private final String column;
    private final List<Object> keys;

    private ExportGroups(SpooledRows rows, String column, List<Object> keys) {
        this.rows = rows;
        this.column = column;
        this.keys = keys;
    }

    /**
     * One group: its key, and the rows carrying it.
     *
     * <p>The bean accessors are not redundant. A template's expression language resolves
     * {@code g.key} by looking for {@code getKey()}; a record's own {@code key()} accessor is
     * invisible to it, and a multisheet report came out with the right sheet names and nothing in
     * them.
     */
    public record Group(Object key, Iterable<Map<String, Object>> rows) {

        public Object getKey() {
            return key;
        }

        public Iterable<Map<String, Object>> getRows() {
            return rows;
        }
    }

    /**
     * Reads the spool once to establish the group order, failing when the rows are not ordered by
     * {@code column}.
     */
    public static ExportGroups of(SpooledRows rows, String column) {
        if (!rows.columns().isEmpty() && !rows.columns().contains(column)) {
            throw new TqlException(UNORDERED, "groupBy: names '" + column
                    + "', which the extraction does not select - it has " + rows.columns());
        }
        List<Object> keys = new ArrayList<>();
        Set<Object> closed = new LinkedHashSet<>();
        Object current = null;
        boolean started = false;
        for (Map<String, Object> row : rows) {
            Object key = row.get(column);
            if (started && java.util.Objects.equals(key, current)) {
                continue;
            }
            if (started) {
                closed.add(current);
            }
            if (closed.contains(key)) {
                throw new TqlException(UNORDERED, "Group '" + key + "' reappears after its rows"
                        + " ended - order the extraction by " + column
                        + ", or the same group would be written more than once");
            }
            keys.add(key);
            current = key;
            started = true;
        }
        return new ExportGroups(rows, column, List.copyOf(keys));
    }

    /** How many groups the rows carry. */
    public int size() {
        return keys.size();
    }

    /** The group keys in the order they appear, which is what a sheet-name list wants. */
    public List<Object> keys() {
        return keys;
    }

    @Override
    public Iterator<Group> iterator() {
        Iterator<Object> keyIterator = keys.iterator();
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return keyIterator.hasNext();
            }

            @Override
            public Group next() {
                Object key = keyIterator.next();
                return new Group(key, () -> new KeyedRows(rows.iterator(), column, key));
            }
        };
    }

    /** The rows carrying one key, read straight off the spool and filtered as they pass. */
    private static final class KeyedRows implements Iterator<Map<String, Object>> {

        private final Iterator<Map<String, Object>> source;
        private final String column;
        private final Object key;
        private Map<String, Object> pending;
        private boolean seen;

        KeyedRows(Iterator<Map<String, Object>> source, String column, Object key) {
            this.source = source;
            this.column = column;
            this.key = key;
        }

        @Override
        public boolean hasNext() {
            while (pending == null && source.hasNext()) {
                Map<String, Object> row = source.next();
                if (java.util.Objects.equals(row.get(column), key)) {
                    pending = row;
                    seen = true;
                } else if (seen) {
                    // Ordered rows mean this group is behind us; stop rather than scan the rest
                    // — and release the reader, which would otherwise stay open on its stream
                    // (and its staging copy) for every group but the last.
                    if (source instanceof AutoCloseable closeable) {
                        try {
                            closeable.close();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }
                    return false;
                }
            }
            return pending != null;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            Map<String, Object> row = pending;
            pending = null;
            return row;
        }
    }
}
