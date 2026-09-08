package io.tesseraql.studio.runtime;

import io.tesseraql.yaml.manifest.AppManifest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Studio surfaces' decision-contract lookup, memoized so a data-browser page render stops
 * paying a full {@code DecisionSets.load} per request.
 *
 * <p>Its epoch is a route reload, which is the right one for this: every reload path — Studio
 * apply, scaffold apply, the file watcher, the manual reload — funnels through
 * {@link RouteReloader}, and the reload's app-wide scope includes the {@code decisions/} tree
 * these contracts are read from.
 *
 * <p>The schema overlay used to be memoized here too, and that was the wrong epoch for it.
 * {@code schema.json} is written by {@code tesseraql schema} and the Maven goal from outside the
 * process, where no reload happens, so a table list cached against a reload could outlive the file
 * it came from indefinitely. It is memoized against the file's own stamp in
 * {@code DocService.schema()} instead, which is both fresher and shared with the five other
 * surfaces that read the overlay.
 */
final class StudioDocCache {

    private final AppManifest manifest;
    private final Map<String, Map<String, String>> contractsByTable = new ConcurrentHashMap<>();

    StudioDocCache(AppManifest manifest) {
        this.manifest = manifest;
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
        contractsByTable.clear();
    }
}
