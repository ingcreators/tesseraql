package io.tesseraql.studio.runtime;

import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Studio surfaces' shared schema/decision lookups, memoized so a data-browser page render
 * stops paying a full {@code DecisionSets.load} plus a {@code schema.json} parse per request.
 * The docs portal already keeps one cache-bearing {@link io.tesseraql.studio.DocService} for
 * the runtime's life; this cache holds Studio's two hot lookups at least as fresh: a hot
 * reload invalidates it (the reload's app-wide scope includes the {@code decisions/} tree the
 * column contracts read, and every reload path — Studio apply, scaffold apply, the file
 * watcher, the manual reload — funnels through {@link RouteReloader}), and the Studio
 * schema-refresh action invalidates it when it rewrites {@code schema.json} in place.
 */
final class StudioDocCache {

    private final AppManifest manifest;
    private volatile List<String> tableNames;
    private final Map<String, Map<String, String>> contractsByTable = new ConcurrentHashMap<>();

    StudioDocCache(AppManifest manifest) {
        this.manifest = manifest;
    }

    /** Every introspected table name across the schema overlay, computed once per epoch. */
    List<String> tableNames() {
        List<String> names = tableNames;
        if (names == null) {
            names = new io.tesseraql.studio.DocService(manifest).tableNames();
            tableNames = names;
        }
        return names;
    }

    /**
     * Column name to decision-contract role for one table (docs/decision-tables.md),
     * memoized per browsed table name.
     */
    Map<String, String> columnContracts(String table) {
        if (table == null) {
            return Map.of();
        }
        return contractsByTable.computeIfAbsent(table,
                name -> new io.tesseraql.studio.DocService(manifest).columnContracts(name));
    }

    /** Drops everything memoized; the next lookup re-reads the current files. */
    void invalidate() {
        tableNames = null;
        contractsByTable.clear();
    }
}
