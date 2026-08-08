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

    /**
     * TQL-VIEW-3316: ejecting a view referenced by more than one route is refused — the flip
     * would fork rendering for the other routes silently. Forking a shared view is an explicit
     * copy-then-eject (docs/view-composition.md wave 1).
     */
    public static final io.tesseraql.core.error.TqlErrorCode SHARED_VIEW = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.VIEW, 3316);

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
        io.tesseraql.yaml.manifest.ViewFile registered = manifest.viewById(html.view());
        if (registered == null) {
            throw new TqlException(ViewSpec.INVALID_VIEW,
                    "view: " + html.view() + " does not resolve to a view document id");
        }
        List<String> referencing = manifest.routes().stream()
                .filter(candidate -> candidate.definition().response() != null
                        && candidate.definition().response().html() != null
                        && html.view().equals(candidate.definition().response().html().view()))
                .map(candidate -> home.relativize(candidate.source()).toString()
                        .replace('\\', '/'))
                .toList();
        if (referencing.size() > 1) {
            throw new TqlException(SHARED_VIEW, "View " + html.view() + " is shared by "
                    + referencing.size() + " routes (" + String.join(", ", referencing)
                    + ") — ejecting would fork rendering for the others. Copy the document"
                    + " under a new id and point this route at the copy first.");
        }
        Path viewFile = registered.source();
        Path viewDir = viewFile.getParent();
        ViewSpec spec = registered.spec();
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
        // The template is named after the view FILE and lands beside it (colocated views eject
        // next to their route exactly as before; a templates/ view ejects into templates/, where
        // the flipped template: reference still resolves).
        String fileName = viewFile.getFileName().toString();
        String templateName = fileName.endsWith(".view.yml")
                ? fileName.substring(0, fileName.length() - ".view.yml".length()) + ".html"
                : fileName + ".html";
        String targetPath = home.relativize(viewDir.resolve(templateName).normalize())
                .toString().replace('\\', '/');
        // The header comment names the FILE the pattern was pinned from — the id lives inside
        // it, the file name is what locates it on disk.
        ScaffoldedFile ejected = ViewEjector.eject(home, viewDir, fileName, spec, fields,
                targetPath);
        ScaffoldWriter.Report report = new ScaffoldWriter().apply(home, List.of(ejected), force);
        if (report.blocked()) {
            return new Result(normalized, targetPath, true);
        }
        try {
            Path routeSource = routeFile.source();
            // The flipped template: reference must resolve from the ROUTE's directory: the bare
            // name for a colocated or templates/ view (both resolve), the route-relative path
            // for the odd view sitting in some other route's directory.
            Path routeDir = routeSource.getParent();
            Path target = viewDir.resolve(templateName).normalize();
            String flipRef = target.getParent().equals(routeDir.toAbsolutePath().normalize())
                    || viewDir.startsWith(home.resolve("templates"))
                            ? templateName
                            : routeDir.toAbsolutePath().normalize().relativize(target)
                                    .toString().replace('\\', '/');
            String flipped = ViewEjector.flipRoute(Files.readString(routeSource), html.view(),
                    flipRef);
            Files.writeString(routeSource, flipped);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new Result(normalized, targetPath, false);
    }
}
