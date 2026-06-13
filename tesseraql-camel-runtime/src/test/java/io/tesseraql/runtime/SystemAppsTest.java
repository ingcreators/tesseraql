package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemAppsTest {

    @TempDir
    Path dir;

    private static final String PING_ROUTE = """
            version: tesseraql/v1
            id: %s
            kind: route
            recipe: query-json
            sql:
              file: ping.sql
              mode: query
            response:
              json:
                body:
                  data: sql.rows
            """;

    private static final String MCP_TOOL = """
            version: tesseraql/v1
            id: %s
            kind: tool
            recipe: query-json
            description: A tool.
            sql:
              file: tool.sql
              mode: query
            """;

    private static final String MCP_RESOURCE = """
            version: tesseraql/v1
            id: %s
            kind: resource
            recipe: query-json
            uri: %s
            description: A resource.
            sql:
              file: tool.sql
              mode: query
            """;

    private static final String MCP_UI = """
            version: tesseraql/v1
            id: %s
            kind: ui
            recipe: query-html
            uri: %s
            description: A UI resource.
            sql:
              file: tool.sql
              mode: query
            response:
              html:
                template: ui.html
            """;

    private Path app(String name, String routeId, String urlDir) throws Exception {
        Path home = dir.resolve(name);
        Path routeDir = home.resolve("web/" + urlDir);
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("get.yml"), PING_ROUTE.formatted(routeId));
        Files.writeString(routeDir.resolve("ping.sql"), "select 1 as ok\n;\n");
        return home;
    }

    /** An app whose only surface is one MCP document (tool/resource/ui) under {@code mcp/}. */
    private Path mcpApp(String name, String document) throws Exception {
        Path home = dir.resolve(name);
        Path mcp = home.resolve("mcp");
        Files.createDirectories(mcp);
        Files.writeString(mcp.resolve("doc.yml"), document);
        Files.writeString(mcp.resolve("tool.sql"), "select 1 as ok\n;\n");
        return home;
    }

    private AppManifest load(Path home) {
        return new ManifestLoader().load(home);
    }

    @Test
    void loadsConfigMountedAppWithMainConfig() throws Exception {
        Path home = app("extra", "extra.ping", "extra/ping");
        AppConfig mainConfig = new AppConfig(Map.of("tesseraql", Map.of("apps",
                Map.of("extra", Map.of("path", home.toString())))), name -> null);

        List<SystemApps.MountedApp> mounted = SystemApps.load(mainConfig, dir.resolve("main"));

        // The classpath also contributes bundled system apps (e.g. iam-admin); find ours.
        SystemApps.MountedApp extra = mounted.stream()
                .filter(m -> m.name().equals("extra"))
                .findFirst().orElseThrow();
        assertThat(extra.manifest().appHome()).isEqualTo(home);
        assertThat(extra.manifest().routes()).hasSize(1);
        // The mounted app's manifest carries the MAIN config (shared datasources/policies).
        assertThat(extra.manifest().config()).isSameAs(mainConfig);
    }

    @Test
    void duplicateRouteIdAcrossAppsIsRejected() throws Exception {
        AppManifest main = load(app("main", "ping.get", "a/ping"));
        AppManifest mounted = load(app("sys", "ping.get", "b/ping"));

        assertThatThrownBy(() -> SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Route id 'ping.get'");
    }

    @Test
    void duplicateEndpointAcrossAppsIsRejected() throws Exception {
        AppManifest main = load(app("main", "a.ping", "shared/ping"));
        AppManifest mounted = load(app("sys", "b.ping", "shared/ping"));

        assertThatThrownBy(() -> SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Endpoint 'GET /shared/ping'");
    }

    @Test
    void distinctAppsPassConflictCheck() throws Exception {
        AppManifest main = load(app("main", "a.ping", "a/ping"));
        AppManifest mounted = load(app("sys", "b.ping", "b/ping"));

        SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted)));
    }

    @Test
    void duplicateMcpToolNameAcrossAppsIsRejected() throws Exception {
        AppManifest main = load(mcpApp("main", MCP_TOOL.formatted("find-orders")));
        AppManifest mounted = load(mcpApp("sys", MCP_TOOL.formatted("find-orders")));

        assertThatThrownBy(() -> SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("MCP tool 'find-orders'");
    }

    @Test
    void duplicateMcpResourceUriAcrossAppsIsRejected() throws Exception {
        AppManifest main = load(mcpApp("main",
                MCP_RESOURCE.formatted("orders", "tesseraql://orders")));
        AppManifest mounted = load(mcpApp("sys",
                MCP_RESOURCE.formatted("recent-orders", "tesseraql://orders")));

        assertThatThrownBy(() -> SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("MCP resource uri 'tesseraql://orders'");
    }

    @Test
    void mcpResourceAndUiResourceShareTheUriNamespaceAcrossApps() throws Exception {
        // A UI resource and a plain resource collide if they declare the same uri, even in
        // different apps (they are served from one shared resource namespace).
        AppManifest main = load(mcpApp("main",
                MCP_RESOURCE.formatted("orders", "tesseraql://orders/board")));
        AppManifest mounted = load(mcpApp("sys",
                MCP_UI.formatted("orders-board", "tesseraql://orders/board")));

        assertThatThrownBy(() -> SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("MCP resource uri 'tesseraql://orders/board'");
    }

    @Test
    void distinctMcpSurfacesAcrossAppsPassConflictCheck() throws Exception {
        AppManifest main = load(mcpApp("main", MCP_TOOL.formatted("find-orders")));
        AppManifest mounted = load(mcpApp("sys",
                MCP_RESOURCE.formatted("recent-orders", "tesseraql://orders")));

        SystemApps.requireNoRouteConflicts(main,
                List.of(new SystemApps.MountedApp("sys", mounted)));
    }
}
