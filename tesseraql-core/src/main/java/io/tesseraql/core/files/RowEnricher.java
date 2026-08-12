package io.tesseraql.core.files;

import java.util.List;
import java.util.Map;

/**
 * Folds a keyed reference into a window of export rows (docs/lookups.md, slice 13b).
 *
 * <p>The seam exists because the two halves live in modules that do not see each other: the rows
 * are read and spooled by the file-transfer service and the SQL producer, while the enrichment
 * knows about 2-way SQL, datasources and the outbound gateway and lives with the compiler. Core
 * owns the interface; the compiler supplies the implementation when a route declares
 * {@code enrich:}; the executors apply it without knowing what it does.
 *
 * <p>A <b>window</b> rather than the whole result, because an export may be larger than memory.
 * The window is the enrichment's own {@code batchSize}, which is the number of keys it was
 * already going to fetch in one statement — so a streaming export costs one reference query per
 * window and holds one window at a time.
 */
@FunctionalInterface
public interface RowEnricher {

    /**
     * The window, with the reference composed onto each row.
     *
     * <p>Returns rows rather than mutating them: the enrichment copies each row before merging,
     * so a row a codec has already written can never change underneath it.
     */
    List<Map<String, Object>> enrich(List<Map<String, Object>> window);
}
