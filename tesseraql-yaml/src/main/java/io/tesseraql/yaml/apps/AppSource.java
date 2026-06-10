package io.tesseraql.yaml.apps;

import java.nio.file.Path;

/**
 * A supplier of one TesseraQL application root (design ch. 32). An app source names an app and
 * materializes its file tree (the {@code web/}, {@code templates/}, {@code batch/}, {@code config/}
 * layout) as a directory the {@link io.tesseraql.yaml.manifest.ManifestLoader} can load.
 *
 * <p>Sources unify how apps reach the runtime: the main app home is a directory, bundled system
 * apps (operations console, Studio, IAM admin) are classpath resources extracted at boot, and
 * future {@code .tqlapp} packages can plug in the same way.
 */
public interface AppSource {

    /** The unique app name (used for the work directory, config keys and diagnostics). */
    String name();

    /**
     * Materializes the app root and returns it. {@code workRoot} is a writable scratch directory
     * sources may extract into ({@code <workRoot>/<name>}); directory-backed sources ignore it and
     * return their directory as-is.
     */
    Path materialize(Path workRoot);
}
