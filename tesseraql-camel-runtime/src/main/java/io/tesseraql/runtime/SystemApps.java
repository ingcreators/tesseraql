package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSources;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mounts the additional apps discovered via {@link AppSources} alongside the main app (design
 * ch. 32): each source is materialized under {@code work/apps} and loaded with the standard
 * {@link ManifestLoader}, so system apps are plain yaml/sql/template trees compiled by the same
 * route compiler as user apps.
 *
 * <p>Mounted apps run with the main app's configuration (datasources, security policies, dialect);
 * their own {@code config/} directory is reserved for future default merging. Route ids and
 * method+path pairs must be unique across all mounted apps.
 */
final class SystemApps {

    private static final Logger LOG = LoggerFactory.getLogger(SystemApps.class);
    private static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.YAML, 1206);

    private SystemApps() {
    }

    /** A mounted app: its source name and its manifest (sharing the main app's config). */
    record MountedApp(String name, AppManifest manifest) {
    }

    /** Loads every enabled app source as a manifest sharing the main app's config. */
    static List<MountedApp> load(AppConfig mainConfig, Path mainAppHome) {
        Path workRoot = mainAppHome.resolve("work/apps");
        List<MountedApp> apps = new ArrayList<>();
        for (AppSource source : AppSources.discover(mainConfig)) {
            Path root = source.materialize(workRoot);
            AppManifest loaded = new ManifestLoader().load(root);
            apps.add(new MountedApp(source.name(), new AppManifest(loaded.appHome(), mainConfig,
                    loaded.routes(), loaded.jobs(), loaded.tools(), loaded.resources(),
                    loaded.uiResources(), loaded.index())));
            LOG.info("Mounted app '{}' from {} ({} routes, {} jobs)",
                    source.name(), root, loaded.routes().size(), loaded.jobs().size());
        }
        return apps;
    }

    /** Rejects duplicate route ids or method+path pairs across the main and mounted apps. */
    static void requireNoRouteConflicts(AppManifest main, List<MountedApp> mounted) {
        Map<String, Path> byId = new HashMap<>();
        Map<String, Path> byEndpoint = new HashMap<>();
        List<AppManifest> all = new ArrayList<>();
        all.add(main);
        mounted.forEach(app -> all.add(app.manifest()));
        for (AppManifest manifest : all) {
            for (RouteFile route : manifest.routes()) {
                Path previous = byId.putIfAbsent(route.definition().id(), route.source());
                if (previous != null) {
                    throw new TqlException(CONFLICT, "Route id '" + route.definition().id()
                            + "' is defined by both " + previous + " and " + route.source());
                }
                String endpoint = route.httpMethod() + " " + route.urlPath();
                previous = byEndpoint.putIfAbsent(endpoint, route.source());
                if (previous != null) {
                    throw new TqlException(CONFLICT, "Endpoint '" + endpoint
                            + "' is defined by both " + previous + " and " + route.source());
                }
            }
        }
    }
}
