package io.tesseraql.core.catalog;

import java.util.Map;

/**
 * The seam a request reads its code catalogs through (docs/lookups.md, decision 8).
 *
 * <p>A catalog is loaded whole and held; a request asks for the current set and resolves from
 * memory. The store is what decides whether a load is due, which is why this is an interface
 * rather than a map: the caching, the per-tenant separation and — from a later slice — the
 * invalidation stamp all live behind it, and the request path stays a lookup.
 */
public interface CatalogStore {

    /**
     * Every declared catalog, loaded. Implementations serve a held copy until it is stale, and
     * a failed refresh keeps the previous one rather than emptying a screen.
     */
    Map<String, CodeCatalog> catalogs();

    /**
     * Re-reads one catalog from its source, whatever the hold says, and returns it.
     *
     * <p>This is the validation path's (docs/lookups.md, decision 11): a cache miss is not an
     * answer. A code added a minute ago must not be refused for the length of the hold, so a
     * value the held copy does not carry is re-checked against the source before it is
     * rejected. The cost lands only on the rejection path.
     */
    CodeCatalog reload(String name);
}
