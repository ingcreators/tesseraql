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
     * Every declared catalog, loaded, with its labels in the requested language
     * (docs/lookups.md, decision 12). Implementations serve a held copy until it is stale, and
     * a failed refresh keeps the previous one rather than emptying a screen.
     *
     * <p>{@code tag} is the surface's resolved locale — a request's for an HTTP route, the
     * export's declared one, the job's, the recipient's for mail. It is never the JVM default:
     * a surface that cannot name a locale is refused at build time, because "the report came
     * out in English because the server's locale was" is this feature's characteristic failure.
     */
    Map<String, CodeCatalog> catalogs(String tag);

    /**
     * One catalog as it was loaded, in every language it carries.
     *
     * <p>This is the validation path's view. Whether a code may be written is a question about
     * the key set, not about names, so it must not be asked of a language: a code with no
     * Japanese label is still a code, and rejecting it because a translation is missing would
     * turn a content gap into a failed transaction.
     */
    CodeCatalog catalog(String name);

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
