package io.tesseraql.compiler.binding;

import io.tesseraql.core.cache.Invalidations;
import io.tesseraql.core.catalog.CatalogStore;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.List;

/**
 * Drops what a command's write made stale (docs/lookups.md decision 13, docs/caching.md
 * decision 5): the code catalogs that read the named tables, the held source results that
 * read them, and — through the per-table version stamp — the same holds on every other node.
 *
 * <p>Placed where the live-view topic emit is placed — after the command processor, so a
 * rollback bypasses it. Invalidating for a write that did not happen would send every reader
 * to the database to reload rows that never changed.
 *
 * <p>The declaration names <b>tables</b>. A maintenance screen for a shared code master upserts
 * a row whose kind is request data, so which catalog is affected is not known until the row is
 * written; the table is known from the route. Over-invalidating costs a handful of small
 * queries, which is precisely the trade a catalog is chosen for — and a held source's next
 * request runs its statement once.
 *
 * <p>The runtime binds one {@link Invalidations} over its catalog store, its result hold and
 * its stamps. A hand-built context that bound only a catalog store keeps the pre-hold
 * behaviour: the store is asked directly, and nothing is stamped.
 */
public final class InvalidationProcessor implements Step {

    private final List<String> tables;

    public InvalidationProcessor(List<String> tables) {
        this.tables = List.copyOf(tables);
    }

    @Override
    public void process(Exchange exchange) {
        Invalidations invalidations = exchange.beans().lookup(
                TesseraqlProperties.INVALIDATIONS_BEAN, Invalidations.class);
        if (invalidations != null) {
            invalidations.invalidate(tables);
            return;
        }
        CatalogStore store = exchange.beans().lookup(TesseraqlProperties.CATALOG_STORE_BEAN,
                CatalogStore.class);
        if (store != null) {
            store.invalidate(tables);
        }
    }
}
