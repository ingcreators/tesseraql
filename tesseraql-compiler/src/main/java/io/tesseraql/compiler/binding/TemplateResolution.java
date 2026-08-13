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

    /** TQL-TPL-2001: template resolution failures (the renderers' render-error code). */
    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.TPL, 2001);

    private TemplateResolution() {
    }

    /**
     * Resolves a route's template: colocated next to the route first, then the shared
     * {@code templates/} root; confined to the app home. Returns the app-home-relative name used
     * with the app's template engine.
     */
    static String resolve(Path appHome, Path routeDir, String template) {
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(template).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : appHome.resolve("templates").resolve(template).normalize();
        if (!file.startsWith(appHome)) {
            throw new TqlException(RENDER_ERROR, "Template escapes app home: " + template);
        }
        if (!Files.isRegularFile(file)) {
            throw new TqlException(RENDER_ERROR, "Template not found: " + template);
        }
        return appHome.relativize(file).toString().replace('\\', '/');
    }
}
