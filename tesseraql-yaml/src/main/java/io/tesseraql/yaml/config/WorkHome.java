package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Path;

/**
 * Resolves the app's work directory (docs/config-consumers.md): the generated-artifact and
 * runtime-state tree that {@code tesseraql.app.work} may relocate. Every consumer — the
 * manifest index pruner, mounted-app materialization, packaging, reports, module caches —
 * resolves through here, so the key is honored everywhere or nowhere; a half-honored
 * relocation key is exactly the emitted-but-dead class this closes.
 *
 * <p>Absent, unresolvable (the scaffolded default chains {@code ${TESSERAQL_WORK_HOME:...}}
 * through {@code ${TESSERAQL_APP_HOME}}, which only the launcher environment defines), or
 * blank, the conventional {@code <appHome>/work} applies. A relative value resolves against
 * the app home.
 */
public final class WorkHome {

    /**
     * The module set a package carries, under the archive's reserved namespace
     * (docs/module-channel.md decision 3). An installed application reads its modules from here
     * and never from {@code work/modules}: packaging resolved them from {@code modules.lock} and
     * the archive was verified with them, while a work directory on a deployed host is whatever
     * the last run happened to leave. A source tree has no such directory and reads
     * {@code work/modules} as before. The constant lives here because both sides of that branch —
     * the packager and the runtime — resolve application paths through this class.
     */
    public static final String BUNDLED_MODULES = ".tesseraql/modules";

    private WorkHome() {
    }

    /** The bundled module directory of an installed application, whether or not it exists. */
    public static Path bundledModules(Path appHome) {
        return appHome.resolve(BUNDLED_MODULES);
    }

    /** The app's work directory: the declared {@code tesseraql.app.work}, else {@code work/}. */
    public static Path resolve(Path appHome, AppConfig config) {
        try {
            String declared = config.getString("tesseraql.app.work").orElse(null);
            if (declared != null && !declared.isBlank()) {
                Path path = Path.of(declared.trim());
                return (path.isAbsolute() ? path : appHome.resolve(path)).normalize();
            }
        } catch (TqlException unresolvable) {
            // The documented default shape cannot resolve outside the launcher environment;
            // that is the conventional layout, not an error.
        }
        return appHome.resolve("work");
    }
}
