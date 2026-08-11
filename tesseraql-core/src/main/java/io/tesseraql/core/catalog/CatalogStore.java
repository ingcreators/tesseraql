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
}
