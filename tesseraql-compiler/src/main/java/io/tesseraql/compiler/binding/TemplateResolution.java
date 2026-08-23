package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a route's {@code template:} reference to the app-home-relative name used with the
 * app's template engine. Resolution works like {@code sql.file}: first relative to the route's
 * own directory (the colocated yml + sql + html unit), falling back to the app's shared
 * {@code templates/} directory for cross-route fragments and layouts; the result is confined to
 * the app home and must exist (fail-fast at build time). Shared by {@link HtmlResponseRenderer},
 * {@link FileResponseRenderer} and {@link ViewBinding} — template resolution is not a renderer
 * concern, so it lives outside the renderers.
 */
final class TemplateResolution {

    /** TQL-TPL-2001: the template could not be resolved or rendered — one rule, declared once. */
    static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.TPL, 2001);

    private TemplateResolution() {
    }

    /**
     * Resolves a route's template: colocated next to the route first, then the shared
     * {@code templates/} root; confined to the app home. Returns the app-home-relative name used
     * with the app's template engine.
     */
    static String resolve(Path appHome, Path routeDir, String template) {
        // The guard used to compare against appHome as it arrived — a relative or
        // ..-carrying app home made it vacuous; ConfinedPath canonicalizes both sides.
        io.tesseraql.core.files.ConfinedPath home = io.tesseraql.core.files.ConfinedPath
                .under(appHome);
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(template).normalize();
        Path candidate = Files.isRegularFile(colocated)
                ? colocated
                : home.root().resolve("templates").resolve(template);
        Path file = home.confine(candidate)
                .orElseThrow(() -> new TqlException(RENDER_ERROR,
                        "Template escapes app home: " + template));
        if (!Files.isRegularFile(file)) {
            throw new TqlException(RENDER_ERROR, "Template not found: " + template);
        }
        return home.root().relativize(file).toString().replace('\\', '/');
    }
}
