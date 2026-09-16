package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.app.RouteFiles;
import java.nio.file.Path;

/**
 * Resolves a route's {@code template:} reference to the app-home-relative name used with the
 * app's template engine. Resolution works like {@code sql.file}: first relative to the route's
 * own directory (the colocated yml + sql + html unit), falling back to the app's shared
 * {@code templates/} directory for cross-route fragments and layouts; the result is confined to
 * the app home and must exist (fail-fast at build time). The rule itself is
 * {@link RouteFiles#page}, the one resolver the linter judges by (docs/audit-low-leads.md
 * slice 14) — an escape and a template that is nowhere are its refusals, with its codes.
 * Shared by {@link HtmlResponseRenderer}, {@link FileResponseRenderer} and
 * {@link ViewBinding} — template resolution is not a renderer concern, so it lives outside
 * the renderers.
 */
final class TemplateResolution {

    private TemplateResolution() {
    }

    /**
     * Resolves a route's template: colocated next to the route first, then the shared
     * {@code templates/} root; confined to the app home. Returns the app-home-relative name used
     * with the app's template engine.
     */
    static String resolve(Path appHome, Path routeDir, String template) {
        Path home = appHome.toAbsolutePath().normalize();
        Path file = RouteFiles.page(home, routeDir, template, "template ");
        return home.relativize(file).toString().replace('\\', '/');
    }
}
