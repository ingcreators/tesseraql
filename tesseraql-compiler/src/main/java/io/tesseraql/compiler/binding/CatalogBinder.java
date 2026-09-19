package io.tesseraql.compiler.binding;

import io.tesseraql.core.catalog.CatalogStore;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.Map;

/**
 * Publishes the app's code catalogs into the execution context under {@code codes}
 * (docs/lookups.md, decision 8).
 *
 * <p>They land in the context rather than in a view's model because that is what makes them
 * survive the ladder: a declarative view's {@code domain:} reference and a hand-owned template
 * that was ejected from it read the very same object, so ejecting a screen cannot quietly lose
 * its names. No export codec and no notifier reads it — a name in a document comes through
 * {@code enrich:} or a named query — so an export route carries no binder (docs/lookups.md,
 * decision 12 as built).
 *
 * <p>Resolution costs no query — the store serves a held load — and the object published is
 * resolved on read (docs/lookups.md, decision 14 as built): a route that never asks for a
 * catalog never loads one, and a catalog that cannot load fails the screen that asked for it,
 * not every request of the app.
 */
public final class CatalogBinder implements Step {

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        CatalogStore store = exchange.beans().lookup(TesseraqlProperties.CATALOG_STORE_BEAN,
                CatalogStore.class);
        if (store == null) {
            return;
        }
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        // The request's resolved locale (docs/lookups.md, decision 12), which LocaleResolution
        // has already published — user preference, then Accept-Language, then the app default.
        // Read here rather than defaulted here: a surface with no request to resolve against
        // is not one that renders a code.
        context.put(TesseraqlProperties.CODES,
                store.catalogs(exchange.getProperty(TesseraqlProperties.LOCALE, String.class)));
    }
}
