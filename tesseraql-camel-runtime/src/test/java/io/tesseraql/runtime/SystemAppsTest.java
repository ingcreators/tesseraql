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

    private Path app(String name, String routeId, String urlDir) throws Exception {
        Path home = dir.resolve(name);
        Path routeDir = home.resolve("web/" + urlDir);
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("get.yml"), PING_ROUTE.formatted(routeId));
        Files.writeString(routeDir.resolve("ping.sql"), "select 1 as ok\n;\n");
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
}
