package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.catalog.CatalogStore;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import java.util.List;

/**
 * Drops the code catalogs a command's write made stale (docs/lookups.md, decision 13).
 *
 * <p>Placed where the live-view topic emit is placed — after the command processor, so a
 * rollback bypasses it. Invalidating a catalog for a write that did not happen would send every
 * reader to the database to reload names that never changed.
 *
 * <p>The declaration names <b>tables</b>. A maintenance screen for a shared code master upserts
 * a row whose kind is request data, so which catalog is affected is not known until the row is
 * written; the table is known from the route. Over-invalidating costs a handful of small
 * queries, which is precisely the trade a catalog is chosen for.
 */
public final class CatalogInvalidateProcessor implements Step {

    private final List<String> tables;

    public CatalogInvalidateProcessor(List<String> tables) {
        this.tables = List.copyOf(tables);
    }

    @Override
    public void process(Exchange exchange) {
        CatalogStore store = exchange.beans().lookup(TesseraqlProperties.CATALOG_STORE_BEAN,
                CatalogStore.class);
        if (store != null) {
            store.invalidate(tables);
        }
    }
}
