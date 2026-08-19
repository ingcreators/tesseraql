package io.tesseraql.runtime;

import io.tesseraql.compiler.RouteCompiler;
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
 * <p>Scope: the {@code web/} routes, the shared definitions that bake into them
 * ({@code decisions/}, {@code rules/}, {@code scope/}, {@code domains/} — a change rebuilds
 * every route), and the {@code workflow/} surface (a change rebuilds the synthesized
 * transition routes). Jobs, consumers, config, and MCP documents still need a restart.
 *
 * <p>Public because the workshop extension (docs/studio-shell.md structural decision 3) drives
 * it from outside this package; the Studio coupling it used to carry — refreshing the Studio
 * explorer and dropping the doc cache after a reload — arrives through {@link #onReload}
 * listeners the extension registers, so this class names no Studio type.
 */
public final class RouteReloader {

    private static final Logger LOG = LoggerFactory.getLogger(RouteReloader.class);

    /**
     * TQL-CAMEL-3103: the route failed to compile during hot reload; a 500 stub serves the
     * compile message on its endpoint until the file is fixed.
     */
    private static final String COMPILE_FAILED = "TQL-CAMEL-3103";

    private final CamelContext context;
    private final Path appHome;
    private final String appName;
    private final List<SystemApps.MountedApp> mountedApps;
    /** Ran after every successful reload — the workshop extension hooks its cache epochs here. */
    private final List<Runnable> reloadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * The app's base path, resolved once. Restated on every REST configuration this class
     * builds: a reloaded or stubbed route re-enters the context-wide configuration, and a hot
     * reload must not quietly move a route out from under the app's prefix
     * (docs/base-path.md).
     */
    private final String basePath;
    private final io.tesseraql.core.expr.ExpressionFunctions functions;
    private AppManifest current;
    /** Per-route content fingerprints (source-directory digests) from the last good reload. */
    private Map<String, String> fingerprints;
    /** The app-wide inputs every compiled route bakes in (config/ + shared definitions). */
    private String appFingerprint;
    /** The workflow/ tree; a change rebuilds the synthesized transition routes. */
    private String workflowFingerprint;

    RouteReloader(CamelContext context, Path appHome, AppManifest current,
            String appName, List<SystemApps.MountedApp> mountedApps,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.context = context;
        this.functions = functions;
        this.appHome = appHome;
        this.current = current;
        this.appName = appName;
        this.mountedApps = List.copyOf(mountedApps);
        this.basePath = io.tesseraql.core.http.BasePaths.normalize(
                current.config().getString("tesseraql.http.basePath").orElse(null));
        this.fingerprints = fingerprintsOf(current);
        this.appFingerprint = appFingerprintOf(appHome);
        this.workflowFingerprint = workflowFingerprintOf(appHome);
    }

    /** One route that failed to compile on reload; its endpoint serves this error as a 500. */
    public record RouteFailure(String id, String method, String path, String error) {
    }

    /** The reload outcome: what changed and what broke. */
    public record Result(List<String> reloaded, List<String> added, List<String> removed,
            List<RouteFailure> failed) {
    }

    /**
     * Registers a listener run after every successful reload — the epoch signal the workshop
     * extension uses to refresh the Studio explorer and drop its memoized doc lookups, and the
     * seam that keeps this class free of Studio types.
     */
    public void onReload(Runnable listener) {
        reloadListeners.add(listener);
    }

    /**
     * The instant-loop reload: like {@link #reload(boolean) reload(false)}, kept routes
     * whose sources did not change are left running untouched — bouncing a route is the
     * risky, expensive part of a reload (a stop/re-add races in-flight requests on its
     * endpoint), so an apply that edits one file bounces one route, not the whole app.
     */
    public synchronized Result reload() {
        return reload(false);
    }

    /**
     * Diffs the re-read manifest against the running routes and applies the delta with
     * per-route failure isolation. {@code force} rebuilds every kept route regardless of
     * content — the manual {@code POST /_tesseraql/studio/reload} recovery hammer.
     */
    public synchronized Result reload(boolean force) {
        // Tolerant load: an unparseable route document is a per-route failure like a compile
        // error, not a reason to abort — only app.yml/config problems still fail the load.
        List<ManifestLoader.BrokenRoute> broken = new ArrayList<>();
        AppManifest reloaded = new ManifestLoader().load(appHome, broken, functions);
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
                context.addRoutes(new RouteCompiler().appName(appName).functions(functions)
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

        // The workflow surface (docs/procurement-demo.md's own friction, generalized): a
        // change under workflow/ rebuilds the synthesized transition routes — the whole
        // set, since commands, resolvers, and the yml live in one directory. A transition
        // that fails to recompile is reported and stays un-mounted (no REST stub to hang
        // it on); the rest keep serving.
        String workflowNow = workflowFingerprintOf(appHome);
        if (rebuildAll || !workflowNow.equals(workflowFingerprint)) {
            Map<String, String> beforeWorkflow = workflowRoutePaths(current);
            Map<String, String> nowWorkflow = workflowRoutePaths(reloaded);
            for (String id : beforeWorkflow.keySet()) {
                try {
                    stopAndRemove(id);
                } catch (Exception ex) {
                    LOG.warn("Could not stop transition route {} before reload: {}", id,
                            ex.getMessage());
                }
                if (!nowWorkflow.containsKey(id)) {
                    removed.add(id);
                }
            }
            for (Map.Entry<String, String> transition : nowWorkflow.entrySet()) {
                String id = transition.getKey();
                try {
                    context.addRoutes(new RouteCompiler().appName(appName).functions(functions)
                            .compile(reloaded, true, Set.of(id)));
                    (beforeWorkflow.containsKey(id) ? reloadedIds : addedIds).add(id);
                } catch (Exception ex) {
                    failed.add(new RouteFailure(id, "POST", transition.getValue(),
                            String.valueOf(ex.getMessage())));
                    LOG.warn("Transition route {} failed to compile on reload: {}", id,
                            ex.getMessage());
                }
            }
        }

        this.current = reloaded;
        this.fingerprints = prints;
        this.appFingerprint = appNow;
        this.workflowFingerprint = workflowNow;
        // The reload's scope includes the shared definitions Studio's memoized lookups read
        // (decisions/ for the data browser's column contracts), so a reload is their epoch —
        // the workshop extension's listeners drop those caches and refresh the explorer here.
        reloadListeners.forEach(Runnable::run);
        LOG.info("Hot reload: {} reloaded, {} added, {} removed, {} failed, {} unchanged",
                reloadedIds.size(), addedIds.size(), removed.size(), failed.size(), unchanged);
        return new Result(reloadedIds, addedIds, removed, failed);
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
     * The app-wide compiled-in inputs: everything under {@code config/} plus the shared
     * definitions — {@code decisions/}, {@code rules/}, {@code scope/}, {@code domains/} —
     * which any route may reference (a decision's rows, a rule's SQL, a scope arm, a
     * domain's constraints all bake into the routes that use them, and cheap-and-correct
     * beats tracking per-route reference graphs). Flags, menus, messages, and templates
     * resolve live at render time and never bake into a route.
     */
    private static String appFingerprintOf(Path appHome) {
        StringBuilder joined = new StringBuilder(digestTree(appHome.resolve("config")));
        for (String shared : List.of("decisions", "rules", "scope", "domains")) {
            joined.append('|').append(digestTree(appHome.resolve(shared)));
        }
        joined.append('|').append(digestViewDocuments(appHome));
        return joined.toString();
    }

    /**
     * View documents bake into the routes that reference them, and since references are by id
     * (docs/view-composition.md wave 1) a shared document may sit outside the referencing
     * route's own directory — so every {@code *.view.yml} under {@code web/} and
     * {@code templates/} joins the app fingerprint, cheap-and-correct like the shared
     * definitions above.
     */
    private static String digestViewDocuments(Path appHome) {
        StringBuilder joined = new StringBuilder();
        for (String root : List.of("web", "templates")) {
            Path tree = appHome.resolve(root);
            if (!java.nio.file.Files.isDirectory(tree)) {
                joined.append("|absent");
                continue;
            }
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(tree)) {
                joined.append('|').append(digest(files.filter(
                        file -> file.getFileName().toString().endsWith(".view.yml"))));
            } catch (java.io.IOException ex) {
                joined.append("|unreadable:").append(ex.getMessage());
            }
        }
        return joined.toString();
    }

    /** The workflow surface: a change rebuilds every synthesized transition route. */
    private static String workflowFingerprintOf(Path appHome) {
        return digestTree(appHome.resolve("workflow"));
    }

    /**
     * The synthesized transition route ids ({@code <workflow>.<transition>}, the id contract
     * {@link RouteCompiler} mounts them under), mapped to their endpoint paths for failure
     * reporting.
     */
    private static Map<String, String> workflowRoutePaths(AppManifest manifest) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (io.tesseraql.yaml.manifest.WorkflowFile workflow : manifest.workflows()) {
            io.tesseraql.yaml.model.WorkflowDefinition def = workflow.definition();
            String basePath = def.basePath() == null
                    ? "/" + def.id()
                    : def.basePath();
            for (io.tesseraql.yaml.model.TransitionSpec transition : def.transitions()) {
                paths.put(def.id() + "." + transition.id(),
                        basePath + "/{key}/" + transition.id());
            }
            for (io.tesseraql.yaml.model.DispatchSpec dispatch : def.dispatch()) {
                paths.put(def.id() + "." + dispatch.id(),
                        basePath + "/{key}/" + dispatch.id());
            }
        }
        return paths;
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
        // The compile message names absolute paths, SQL text, and table and column names. The
        // stub is mounted in place of the route's own security chain, so it can be reached
        // without credentials - the diagnostics belong in the log, not in the response, the same
        // rule ErrorResponseRenderer follows for every other failure.
        LOG.warn("Route {} failed to compile; serving a {} stub: {}", id, COMPILE_FAILED,
                cause.getMessage());
        try {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    // Pinned for the same reason RouteCompiler pins it
                    // (docs/transition-engine.md track E): topology is a choice. The base path
                    // is restated for the same reason: a reloaded route re-enters the same
                    // REST configuration, and a hot reload must not quietly move a route out
                    // from under the app's prefix (docs/base-path.md).
                    restConfiguration().component("platform-http").inlineRoutes(true)
                            .contextPath(basePath.isEmpty() ? null : basePath);
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
                        exchange.getMessage().setBody("{\"error\":{\"code\":\"" + COMPILE_FAILED
                                + "\",\"message\":\"Route failed to compile; see the server"
                                + " log for the cause\"}}");
                    });
                }
            });
        } catch (Exception stubFailure) {
            LOG.error("Could not install the 500 stub for {}; the endpoint answers 404 until the"
                    + " definition is fixed", id, stubFailure);
        }
    }

    /** The web-route ids the running context currently serves (tests and diagnostics). */
    public synchronized Set<String> currentIds() {
        return new LinkedHashSet<>(byId(current).keySet());
    }
}
