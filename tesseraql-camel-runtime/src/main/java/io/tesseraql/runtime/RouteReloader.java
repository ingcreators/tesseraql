package io.tesseraql.runtime;

import io.tesseraql.compiler.RouteCompiler;
import io.tesseraql.studio.StudioService;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.camel.CamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hot-reloads compiled route bodies in a running context after Studio applies an edit (design
 * ch. 16.8). To stay safe, only routes that already exist (same id) are reloaded: their
 * {@code direct:} bodies are stopped, removed, and rebuilt from the re-read manifest while the REST
 * consumers stay in place. New routes and removed routes are left for a full restart.
 */
final class RouteReloader {

    private static final Logger LOG = LoggerFactory.getLogger(RouteReloader.class);

    private final CamelContext context;
    private final Path appHome;
    private final StudioService studio;
    private AppManifest current;

    RouteReloader(CamelContext context, Path appHome, AppManifest current, StudioService studio) {
        this.context = context;
        this.appHome = appHome;
        this.current = current;
        this.studio = studio;
    }

    /** Reloads existing routes from the current source and returns the refreshed Studio explorer. */
    synchronized StudioService.Explorer reload() {
        AppManifest reloaded = new ManifestLoader().load(appHome);

        Set<String> reloadedIds = new LinkedHashSet<>();
        reloaded.routes().forEach(route -> reloadedIds.add(route.definition().id()));

        Set<String> reloadable = new LinkedHashSet<>();
        for (RouteFile route : current.routes()) {
            String id = route.definition().id();
            if (reloadedIds.contains(id)) {
                reloadable.add(id);
            }
        }

        try {
            for (String id : reloadable) {
                if (context.getRoute(id) != null) {
                    context.getRouteController().stopRoute(id);
                    context.removeRoute(id);
                }
            }
            // Camel inlines each REST consumer with its body into a single route, so rebuild the
            // full route (REST + body) for the reloaded ids; the old ones were removed above.
            context.addRoutes(new RouteCompiler().compile(reloaded, true, reloadable));
        } catch (Exception ex) {
            throw new IllegalStateException("Route reload failed: " + ex.getMessage(), ex);
        }

        this.current = reloaded;
        LOG.info("Hot-reloaded {} route(s): {}", reloadable.size(), reloadable);
        return studio.reload();
    }
}
