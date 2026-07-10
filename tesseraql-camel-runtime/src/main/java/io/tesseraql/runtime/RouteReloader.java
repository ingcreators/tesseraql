package io.tesseraql.runtime;

import io.tesseraql.compiler.RouteCompiler;
import io.tesseraql.studio.StudioService;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hot-reloads the app's web routes in the running context after Studio applies an edit — the
 * instant loop (roadmap Phase 42, design ch. 16.8): "save and it is serving" holds for creation
 * and removal, not only edits.
 *
 * <p>Each reload re-reads the manifest, re-runs the cross-app route-conflict guard, and diffs
 * the web routes against the last good manifest: kept ids are rebuilt in place, <b>new ids
 * mount</b> (Camel inlines each REST consumer with its body into one addable route), and
 * <b>removed ids un-mount</b>. Every route compiles individually, so one broken definition takes
 * only itself out — it serves a clear 500 carrying its compile error while its neighbors keep
 * serving. A manifest that fails to <i>load</i> (malformed YAML) still aborts the reload as a
 * whole: there is nothing partial to diff against.
 *
 * <p>Scope: the {@code web/} routes. Jobs, consumers, and MCP documents still need a restart.
 */
final class RouteReloader {

    private static final Logger LOG = LoggerFactory.getLogger(RouteReloader.class);

    private final CamelContext context;
    private final Path appHome;
    private final StudioService studio;
    private final String appName;
    private final List<SystemApps.MountedApp> mountedApps;
    private AppManifest current;
    /** Per-route content fingerprints (source-directory digests) from the last good reload. */
    private Map<String, String> fingerprints;
    /** The app-wide inputs every compiled route bakes in (config/); a change rebuilds all. */
    private String appFingerprint;

    RouteReloader(CamelContext context, Path appHome, AppManifest current, StudioService studio,
            String appName, List<SystemApps.MountedApp> mountedApps) {
        this.context = context;
        this.appHome = appHome;
        this.current = current;
        this.studio = studio;
        this.appName = appName;
        this.mountedApps = List.copyOf(mountedApps);
        this.fingerprints = fingerprintsOf(current);
        this.appFingerprint = appFingerprintOf(appHome);
    }

    /** One route that failed to compile on reload; its endpoint serves this error as a 500. */
    public record RouteFailure(String id, String method, String path, String error) {
    }

    /** The reload outcome: what changed, what broke, and the refreshed Studio explorer. */
    public record Result(List<String> reloaded, List<String> added, List<String> removed,
            List<RouteFailure> failed, StudioService.Explorer explorer) {
    }

    /**
     * The instant-loop reload: like {@link #reload(boolean) reload(false)}, kept routes
     * whose sources did not change are left running untouched — bouncing a route is the
     * risky, expensive part of a reload (a stop/re-add races in-flight requests on its
     * endpoint), so an apply that edits one file bounces one route, not the whole app.
     */
    synchronized Result reload() {
        return reload(false);
    }

    /**
     * Diffs the re-read manifest against the running routes and applies the delta with
     * per-route failure isolation. {@code force} rebuilds every kept route regardless of
     * content — the manual {@code POST /_tesseraql/studio/reload} recovery hammer.
     */
    synchronized Result reload(boolean force) {
        // Tolerant load: an unparseable route document is a per-route failure like a compile
        // error, not a reason to abort — only app.yml/config problems still fail the load.
        List<ManifestLoader.BrokenRoute> broken = new ArrayList<>();
        AppManifest reloaded = new ManifestLoader().load(appHome, broken);
        // The structural guard spans every hosted app (startup parity): a new route colliding
        // with another app's endpoint aborts the reload with the conflict named.
        SystemApps.requireNoRouteConflicts(reloaded, mountedApps);

        Map<String, RouteFile> now = byId(reloaded);
        Map<String, RouteFile> before = byId(current);

        // A broken document that previously served keeps its endpoint (as a 500 stub carrying
        // the parse error) instead of being reported as a removal.
        Map<Path, RouteFile> beforeBySource = new LinkedHashMap<>();
        before.values().forEach(route -> beforeBySource.put(normalize(route.source()), route));
        List<RouteFailure> failed = new ArrayList<>();
        Set<String> brokenIds = new LinkedHashSet<>();
        for (ManifestLoader.BrokenRoute b : broken) {
            RouteFile old = beforeBySource.get(normalize(b.source()));
            if (old != null && old.definition().id() != null) {
                brokenIds.add(old.definition().id());
                failed.add(new RouteFailure(old.definition().id(), old.httpMethod(),
                        old.urlPath(), b.error()));
            } else {
                failed.add(new RouteFailure(null, null,
                        appHome.toAbsolutePath().normalize()
                                .relativize(normalize(b.source())).toString().replace('\\', '/'),
                        b.error()));
            }
            LOG.warn("Route document {} failed to parse on reload: {}", b.source(), b.error());
        }

        List<String> added = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        now.keySet().forEach(id -> (before.containsKey(id) ? kept : added).add(id));
        before.keySet().forEach(id -> {
            if (!now.containsKey(id) && !brokenIds.contains(id)) {
                removed.add(id);
            }
        });

        // Content diff (the instant-loop default): a kept route whose source directory —
        // its yml, 2-way SQL, and templates live together — and the app-wide config are
        // both unchanged keeps SERVING, untouched. Only genuinely changed routes bounce:
        // the stop/re-add is the risky part of a reload, so the delta stays minimal.
        Map<String, String> prints = fingerprintsOf(reloaded);
        String appNow = appFingerprintOf(appHome);
        boolean rebuildAll = force || !appNow.equals(appFingerprint);
        List<String> rebuild = new ArrayList<>();
        int unchanged = 0;
        for (String id : kept) {
            if (!rebuildAll && prints.get(id) != null
                    && prints.get(id).equals(fingerprints.get(id))) {
                unchanged++;
            } else {
                rebuild.add(id);
            }
        }

        for (String id : brokenIds) {
            RouteFile old = before.get(id);
            try {
                stopAndRemove(id);
            } catch (Exception ex) {
                LOG.warn("Could not stop broken route {} before stubbing it: {}", id,
                        ex.getMessage());
            }
            installStub(old, new IllegalStateException(failureFor(failed, id)));
        }

        for (String id : removed) {
            try {
                stopAndRemove(id);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "Could not un-mount removed route " + id + ": " + ex.getMessage(), ex);
            }
        }

        List<String> reloadedIds = new ArrayList<>();
        List<String> addedIds = new ArrayList<>();
        List<String> changes = new ArrayList<>(rebuild);
        changes.addAll(added);
        for (String id : changes) {
            try {
                stopAndRemove(id);
                context.addRoutes(new RouteCompiler().appName(appName)
                        .compile(reloaded, true, Set.of(id)));
                (before.containsKey(id) ? reloadedIds : addedIds).add(id);
            } catch (Exception ex) {
                // Per-route isolation (roadmap Phase 42): the broken definition serves a clear
                // 500 carrying its compile error; every other route keeps serving.
                RouteFile route = now.get(id);
                failed.add(new RouteFailure(id, route.httpMethod(), route.urlPath(),
                        String.valueOf(ex.getMessage())));
                installStub(route, ex);
                LOG.warn("Route {} failed to compile on reload; serving a 500 stub: {}", id,
                        ex.getMessage());
            }
        }

        this.current = reloaded;
        this.fingerprints = prints;
        this.appFingerprint = appNow;
        LOG.info("Hot reload: {} reloaded, {} added, {} removed, {} failed, {} unchanged",
                reloadedIds.size(), addedIds.size(), removed.size(), failed.size(), unchanged);
        return new Result(reloadedIds, addedIds, removed, failed, studio.reload());
    }

    /** Per-route content fingerprints: the digest of each route's source directory. */
    private static Map<String, String> fingerprintsOf(AppManifest manifest) {
        Map<Path, String> byDirectory = new LinkedHashMap<>();
        Map<String, String> prints = new LinkedHashMap<>();
        for (RouteFile route : manifest.routes()) {
            if (route.definition().id() != null) {
                prints.put(route.definition().id(), byDirectory.computeIfAbsent(
                        normalize(route.source()).getParent(), RouteReloader::digestDirectory));
            }
        }
        return prints;
    }

    /**
     * The app-wide compiled-in inputs: everything under {@code config/}. Flags, menus,
     * messages, and templates resolve live at render time and never bake into a route.
     */
    private static String appFingerprintOf(Path appHome) {
        return digestTree(appHome.resolve("config"));
    }

    /** Digest of a directory's immediate regular files (name + bytes, sorted). */
    private static String digestDirectory(Path directory) {
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(directory)) {
            return digest(files);
        } catch (java.io.IOException ex) {
            // An unreadable directory reads as changed, so the route safely rebuilds.
            return "unreadable:" + ex.getMessage();
        }
    }

    /** Digest of a whole tree (the config directory nests environment overlays). */
    private static String digestTree(Path root) {
        if (!java.nio.file.Files.isDirectory(root)) {
            return "absent";
        }
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(root)) {
            return digest(files);
        } catch (java.io.IOException ex) {
            return "unreadable:" + ex.getMessage();
        }
    }

    private static String digest(java.util.stream.Stream<Path> files) throws java.io.IOException {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest
                    .getInstance("SHA-256");
            List<Path> regular = files.filter(java.nio.file.Files::isRegularFile)
                    .sorted().toList();
            for (Path file : regular) {
                sha.update(file.getFileName().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(java.nio.file.Files.readAllBytes(file));
                sha.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Path normalize(Path source) {
        return source.toAbsolutePath().normalize();
    }

    private static String failureFor(List<RouteFailure> failed, String id) {
        return failed.stream().filter(f -> id.equals(f.id())).map(RouteFailure::error)
                .findFirst().orElse("unparseable route document");
    }

    private static Map<String, RouteFile> byId(AppManifest manifest) {
        Map<String, RouteFile> routes = new LinkedHashMap<>();
        for (RouteFile route : manifest.routes()) {
            if (route.definition().id() != null) {
                routes.put(route.definition().id(), route);
            }
        }
        return routes;
    }

    private void stopAndRemove(String id) throws Exception {
        if (context.getRoute(id) != null) {
            context.getRouteController().stopRoute(id);
            context.removeRoute(id);
        }
    }

    /**
     * Mounts a 500 stub on the broken route's endpoint so the failure is visible where the
     * route lives (a JSON error carrying the compile message), instead of a misleading 404.
     */
    private void installStub(RouteFile route, Exception cause) {
        String id = route.definition().id();
        String message = String.valueOf(cause.getMessage()).replace("\\", "\\\\")
                .replace("\"", "'").replace("\n", " ");
        try {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    restConfiguration().component("platform-http");
                    String direct = "direct:" + id;
                    switch (route.httpMethod() == null ? "GET" : route.httpMethod()) {
                        case "POST" -> rest().post(route.urlPath()).to(direct);
                        case "PUT" -> rest().put(route.urlPath()).to(direct);
                        case "PATCH" -> rest().patch(route.urlPath()).to(direct);
                        case "DELETE" -> rest().delete(route.urlPath()).to(direct);
                        default -> rest().get(route.urlPath()).to(direct);
                    }
                    from(direct).routeId(id).process(exchange -> {
                        exchange.getMessage().setHeader(
                                org.apache.camel.Exchange.HTTP_RESPONSE_CODE, 500);
                        exchange.getMessage().setHeader(org.apache.camel.Exchange.CONTENT_TYPE,
                                "application/json; charset=utf-8");
                        exchange.getMessage().setBody("{\"error\":{\"code\":\"TQL-CAMEL-3103\","
                                + "\"message\":\"Route failed to compile: " + message + "\"}}");
                    });
                }
            });
        } catch (Exception stubFailure) {
            LOG.error("Could not install the 500 stub for {}; the endpoint answers 404 until the"
                    + " definition is fixed", id, stubFailure);
        }
    }

    /** The web-route ids the running context currently serves (tests and diagnostics). */
    synchronized Set<String> currentIds() {
        return new LinkedHashSet<>(byId(current).keySet());
    }
}
