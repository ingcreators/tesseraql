package io.tesseraql.studio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecGenerator;
import io.tesseraql.yaml.docs.RouteSpecModel;
import io.tesseraql.yaml.manifest.AppManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The backend for the in-Studio documentation portal (documentation portal v1): it serves the
 * spec-layer model for the app's routes and the Markdown bodies, all read-only.
 *
 * <p>Following the artifact-read boundary, it prefers the deterministic {@code spec.json} the build
 * packages under {@link #SPEC_PATH} (the full model, including the test cross-references that live
 * above {@code tesseraql-yaml}). When that artifact is absent — an unpackaged source/dev run — it
 * falls back to a reduced live model generated from the manifest by the yaml-side
 * {@link RouteSpecGenerator} (routes/SQL/migrations only, no test cross-references), so the portal
 * works in edit mode without pulling the test runner into Studio's dependencies.
 *
 * <p>All file access is confined to the app home (no {@code ../} traversal, design ch. 20.2).
 */
public final class DocService {

    /** App-home-relative location the build packages the documentation spec at (see AppPackager). */
    public static final String SPEC_PATH = ".tesseraql/docs/spec.json";

    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.STUDIO, 4003);
    private static final TqlErrorCode READ_ERROR = new TqlErrorCode(TqlDomain.STUDIO, 4041);
    private static final TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.STUDIO, 4042);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AppManifest manifest;
    private final Path appHome;

    public DocService(AppManifest manifest) {
        this.manifest = manifest;
        this.appHome = manifest.appHome();
    }

    /** The application name shown in the portal chrome. */
    public String appName() {
        return manifest.config().getString("tesseraql.app.name").orElse("app");
    }

    /** Whether the deterministic spec.json artifact was packaged with the app (vs. a live run). */
    public boolean hasPackagedSpec() {
        return Files.isRegularFile(appHome.resolve(SPEC_PATH));
    }

    /**
     * The documentation spec: the packaged {@code spec.json} when present (the full model with test
     * cross-references), otherwise a reduced live model from the manifest (no test cross-references).
     */
    public DocSpec spec() {
        Path spec = appHome.resolve(SPEC_PATH);
        if (Files.isRegularFile(spec)) {
            try {
                return MAPPER.readValue(spec.toFile(), DocSpec.class);
            } catch (IOException ex) {
                throw new TqlException(READ_ERROR,
                        "Failed to read " + SPEC_PATH + ": " + ex.getMessage());
            }
        }
        RouteSpecModel live = new RouteSpecGenerator().generate(manifest);
        List<RouteEntry> routes = new ArrayList<>();
        for (RouteSpec route : live.routes()) {
            routes.add(new RouteEntry(route, List.of()));
        }
        return new DocSpec(routes, live.migrations());
    }

    /** The doc entry for one route id, or {@code null} when no such route exists. */
    public RouteEntry route(String id) {
        for (RouteEntry entry : spec().routes()) {
            if (entry.route() != null && id.equals(entry.route().id())) {
                return entry;
            }
        }
        return null;
    }

    /** Reads a hand-written Markdown doc under the app home and renders it to CSP-safe HTML. */
    public String markdown(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new TqlException(NOT_FOUND, "No such doc: " + relativePath);
        }
        try {
            return DocMarkdown.toHtml(Files.readString(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolve(String relativePath) {
        Path resolved = appHome.resolve(relativePath).normalize();
        if (!resolved.startsWith(appHome)) {
            throw new TqlException(TRAVERSAL,
                    "Path escapes app home (design ch. 20.2): " + relativePath);
        }
        return resolved;
    }

    /**
     * The documentation spec model (the studio-side mirror of the build's {@code spec.json}): each
     * route's spec with its covering test cases, plus the migration listing.
     */
    public record DocSpec(List<RouteEntry> routes, List<RouteSpecModel.Migration> migrations) {

        public DocSpec {
            routes = routes == null ? List.of() : List.copyOf(routes);
            migrations = migrations == null ? List.of() : List.copyOf(migrations);
        }
    }

    /** One route's reference: its spec and the test cases that cover it (empty in a live run). */
    public record RouteEntry(RouteSpec route, List<TestRef> tests) {

        public RouteEntry {
            tests = tests == null ? List.of() : List.copyOf(tests);
        }
    }

    /** A covering test case projected to the facts the portal shows. */
    public record TestRef(String name, String kind, String target) {
    }
}
