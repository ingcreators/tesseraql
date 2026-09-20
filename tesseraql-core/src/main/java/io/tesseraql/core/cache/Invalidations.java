package io.tesseraql.core.cache;

import io.tesseraql.core.catalog.CatalogStore;
import java.util.Collection;

/**
 * What a command's {@code invalidates: [table, …]} reaches (docs/caching.md decision 5): the
 * code catalogs that read the tables, the held source results that read them, and the
 * per-table version stamp that carries the drop to every other node. One call after the
 * commit; a rollback never reaches it.
 *
 * <p>Order matters for correctness on this node: the local holds are dropped before the stamp
 * is raised, so a reader racing the write sees either the old hold or a reload — never a
 * stamp that says "current" over an entry that is not.
 */
public interface Invalidations {

    /** Drops every local hold over any of {@code tables} and raises their versions. */
    void invalidate(Collection<String> tables);

    /**
     * The runtime's: the catalog store (or {@code null} when the app declares no catalogs), the
     * result hold (or {@code null} when it declares no held source), and the stamps both read.
     */
    static Invalidations of(CatalogStore catalogs, ResultHold hold, TableStamps stamps) {
        return tables -> {
            if (tables == null || tables.isEmpty()) {
                return;
            }
            if (catalogs != null) {
                catalogs.invalidate(tables);
            }
            if (hold != null) {
                hold.invalidate(tables);
            }
            stamps.bump(tables);
        };
    }
}
