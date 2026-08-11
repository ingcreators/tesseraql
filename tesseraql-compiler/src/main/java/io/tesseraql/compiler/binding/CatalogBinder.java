package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.catalog.CatalogStore;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Publishes the app's code catalogs into the execution context under {@code codes}
 * (docs/lookups.md, decision 8).
 *
 * <p>They land in the context rather than in a view's model because that is what makes them
 * survive the ladder: a declarative view's {@code domain:} reference and a hand-owned template
 * that was ejected from it read the very same object, so ejecting a screen cannot quietly lose
 * its names. An export template and a mail template read it too, for the same reason.
 *
 * <p>Resolution costs no query — the store serves a held load — so a page showing twenty coded
 * columns costs nothing beyond the map lookups it makes.
 */
public final class CatalogBinder implements Processor {

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        CatalogStore store = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.CATALOG_STORE_BEAN, CatalogStore.class);
        if (store == null) {
            return;
        }
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        context.put(TesseraqlProperties.CODES, store.catalogs());
    }
}
