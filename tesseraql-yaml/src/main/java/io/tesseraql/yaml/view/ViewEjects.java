package io.tesseraql.yaml.view;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.scaffold.ScaffoldWriter;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The eject-view orchestration (docs/declarative-views.md L3, docs/page-builder.md D2),
 * shared by the CLI command and Studio's {@code studio.ejectView} service: locate the
 * route, resolve its view document, render the pattern once through {@link ViewEjector},
 * write the checksum-stamped template via {@link ScaffoldWriter}, and flip the route from
 * {@code view:} to {@code template:}. A blocked write (the target exists with hand edits
 * and {@code force} is off) returns without flipping — nothing half-ejects.
 */
public final class ViewEjects {

    /** The outcome: the app-relative template path, or blocked with nothing changed. */
    public record Result(String routePath, String templatePath, boolean blocked) {
    }

    private ViewEjects() {
    }

    /**
     * Ejects {@code routePath} (app-relative, e.g. {@code web/items/get.yml}). Throws
     * {@code TQL-VIEW-*} when the route is unknown, declares no {@code response.html.view},
     * or the view/spec cannot eject (the {@link ViewEjector} preconditions).
     */
    public static Result eject(Path appHome, AppManifest manifest, String routePath,
            boolean force) {
        Path home = appHome.toAbsolutePath().normalize();
        String normalized = routePath.replace('\\', '/');
        RouteFile routeFile = manifest.routes().stream()
                .filter(candidate -> home.relativize(candidate.source()).toString()
                        .replace('\\', '/').equals(normalized))
                .findFirst()
                .orElseThrow(() -> new TqlException(ViewSpec.INVALID_VIEW,
                        "No route at " + normalized));
        var html = routeFile.definition().response() == null
                ? null
                : routeFile.definition().response().html();
        if (html == null || html.view() == null) {
            throw new TqlException(ViewSpec.INVALID_VIEW,
                    "Route " + normalized + " declares no response.html.view — nothing to eject");
        }
        Path routeDir = routeFile.source().getParent();
        Path viewFile = routeDir.resolve(html.view()).normalize();
        if (!Files.isRegularFile(viewFile)) {
            viewFile = home.resolve("templates").resolve(html.view()).normalize();
        }
        ViewSpec spec = ViewSpec.parse(viewFile);
        List<ViewFields.FieldDef> fields = List.of();
        if (ViewSpec.FORM.equals(spec.view())) {
            RouteFile action = manifest.routes().stream()
                    .filter(candidate -> "POST".equalsIgnoreCase(candidate.httpMethod())
                            && candidate.urlPath().equals(spec.action()))
                    .findFirst()
                    .orElseThrow(() -> new TqlException(ViewSpec.INVALID_VIEW,
                            "The view's action " + spec.action() + " matches no POST route"));
            fields = ViewFields.derive(html.view(), spec, action.definition().input());
        }
        String templateName = html.view().endsWith(".view.yml")
                ? html.view().substring(0, html.view().length() - ".view.yml".length()) + ".html"
                : html.view() + ".html";
        String targetPath = home.relativize(routeDir.resolve(templateName).normalize())
                .toString().replace('\\', '/');
        ScaffoldedFile ejected = ViewEjector.eject(home, routeDir, html.view(), spec, fields,
                targetPath);
        ScaffoldWriter.Report report = new ScaffoldWriter().apply(home, List.of(ejected), force);
        if (report.blocked()) {
            return new Result(normalized, targetPath, true);
        }
        try {
            Path routeSource = routeFile.source();
            String flipped = ViewEjector.flipRoute(Files.readString(routeSource), html.view(),
                    templateName);
            Files.writeString(routeSource, flipped);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new Result(normalized, targetPath, false);
    }
}
