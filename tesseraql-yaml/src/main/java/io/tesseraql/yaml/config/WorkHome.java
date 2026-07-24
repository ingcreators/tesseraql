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

    private WorkHome() {
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
