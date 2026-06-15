package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSources;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.ResourceFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.manifest.UiResourceFile;
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
                    loaded.uiResources(), loaded.consumers(), loaded.scopes(), loaded.index())));
            LOG.info("Mounted app '{}' from {} ({} routes, {} jobs)",
                    source.name(), root, loaded.routes().size(), loaded.jobs().size());
        }
        return apps;
    }

    /**
     * Rejects collisions across the main and mounted apps: duplicate HTTP route ids or method+path
     * pairs, and - since the runtime serves every app's MCP surface from one shared
     * {@code /_tesseraql/mcp} endpoint (roadmap Phase 24 mounted-app tools) - duplicate MCP tool
     * names, resource/UI uris, or compiled MCP route ids.
     *
     * <p>An MCP tool's id is its tool name; resources and UI resources share a single uri namespace
     * (the within-app duplicate-uri lint, {@code TQL-MCP-1007}, cannot see across apps). The
     * compiled camel route ids ({@code mcp.<id>}, {@code mcp.resource.<id>}, {@code mcp.ui.<id>})
     * are checked too, so two apps declaring the same id within one kind fail here with a clear
     * message rather than as a raw duplicate-route-id error when the routes are added to the
     * context.
     */
    static void requireNoRouteConflicts(AppManifest main, List<MountedApp> mounted) {
        Map<String, Path> byRouteId = new HashMap<>();
        Map<String, Path> byEndpoint = new HashMap<>();
        Map<String, Path> byMcpToolName = new HashMap<>();
        Map<String, Path> byMcpUri = new HashMap<>();
        Map<String, Path> byMcpRouteId = new HashMap<>();
        List<AppManifest> all = new ArrayList<>();
        all.add(main);
        mounted.forEach(app -> all.add(app.manifest()));
        for (AppManifest manifest : all) {
            for (RouteFile route : manifest.routes()) {
                String id = route.definition().id();
                requireUnique(byRouteId, id, route.source(), "Route id '" + id + "'");
                String endpoint = route.httpMethod() + " " + route.urlPath();
                requireUnique(byEndpoint, endpoint, route.source(), "Endpoint '" + endpoint + "'");
            }
            for (ToolFile tool : manifest.tools()) {
                String id = tool.definition().id();
                requireUnique(byMcpToolName, id, tool.source(), "MCP tool '" + id + "'");
                requireUnique(byMcpRouteId, "mcp." + id, tool.source(),
                        "MCP route 'mcp." + id + "'");
            }
            for (ResourceFile resource : manifest.resources()) {
                String id = resource.definition().id();
                requireUnique(byMcpUri, resource.uri(), resource.source(),
                        "MCP resource uri '" + resource.uri() + "'");
                requireUnique(byMcpRouteId, "mcp.resource." + id, resource.source(),
                        "MCP route 'mcp.resource." + id + "'");
            }
            for (UiResourceFile ui : manifest.uiResources()) {
                String id = ui.definition().id();
                requireUnique(byMcpUri, ui.uri(), ui.source(),
                        "MCP resource uri '" + ui.uri() + "'");
                requireUnique(byMcpRouteId, "mcp.ui." + id, ui.source(),
                        "MCP route 'mcp.ui." + id + "'");
            }
        }
    }

    /** Registers a key as owned by {@code source}, failing with {@code what} on a second owner. */
    private static void requireUnique(Map<String, Path> seen, String key, Path source,
            String what) {
        Path previous = seen.putIfAbsent(key, source);
        if (previous != null) {
            throw new TqlException(CONFLICT,
                    what + " is defined by both " + previous + " and " + source);
        }
    }
}
