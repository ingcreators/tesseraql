package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * The fully loaded TesseraQL application: resolved configuration, compiled-ready route files, and
 * a reproducibility index (design ch. 3, 22.20).
 *
 * @param appHome the external app home directory (design ch. 2.5, 4)
 * @param config  merged, placeholder-resolving configuration
 * @param routes  route files discovered under {@code web/}
 * @param index   checksum index of the manifest source files
 */
public record AppManifest(Path appHome, AppConfig config, List<RouteFile> routes, ManifestIndex index) {

    public AppManifest {
        routes = List.copyOf(routes);
    }
}
