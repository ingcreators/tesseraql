package io.tesseraql.core.rows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Canonical join keys for in-memory row composition (docs/lookups.md, decision 7).
 *
 * <p>The two sides of a composition arrive from different statements and often different
 * drivers, so the same business key can be an {@code INTEGER 1} on one side and a
 * {@code BIGINT 1} on the other. A key is therefore compared by its canonical text rather than
 * by object equality.
 *
 * <p>A composite key normalizes element-wise into a list, so the single-column and
 * multi-column cases are one code path — the one {@code nest:}, {@code enrich:}, and code
 * catalogs all join through.
 */
public final class JoinKeys {

    private JoinKeys() {
    }

    /**
     * The canonical key of {@code row} over {@code columns}, in the order given. A single
     * column yields the canonical value itself; several yield a list of them, so equal keys
     * are equal objects either way.
     */
    public static Object of(Map<String, Object> row, List<String> columns) {
        if (columns.size() == 1) {
            return value(row.get(columns.get(0)));
        }
        List<Object> key = new ArrayList<>(columns.size());
        for (String column : columns) {
            key.add(value(row.get(column)));
        }
        // Not List.copyOf: a key column may be null, and copyOf refuses null elements.
        return Collections.unmodifiableList(key);
    }

    /** The canonical form of one key value; {@code null} stays null rather than becoming "null". */
    public static Object value(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
