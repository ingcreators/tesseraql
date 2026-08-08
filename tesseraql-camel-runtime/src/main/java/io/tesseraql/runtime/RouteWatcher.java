package io.tesseraql.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code serve --watch} file watcher (the editor-first instant loop): saves under the
 * app's {@code web/} tree hot-reload through the exact {@link RouteReloader} Studio's apply
 * uses, so "save in your own editor and it is serving" holds without a click in Studio.
 *
 * <p>Scope is the reload's scope: the {@code web/} routes plus everything that bakes into
 * them — the shared definitions ({@code decisions/}, {@code rules/}, {@code scope/},
 * {@code domains/}), whose change rebuilds every route, and {@code workflow/}, whose change
 * rebuilds the synthesized transition routes. App-level {@code templates/} and
 * {@code messages/} resolve live at render time (a reload would refresh nothing),
 * {@code config/} can redefine datasources and security (a partial refresh would mislead),
 * and jobs/consumers need a restart — so none of those are watched, and {@code work/}
 * (build output) never is.
 *
 * <p>A burst of events (editors save through temp files and fire several) coalesces into one
 * reload after a quiet period; obvious editor noise (backup/swap/temp files, dotfiles) is
 * ignored. A reload failure never kills the watcher or the server: the reloader already
 * isolates a broken definition to its own endpoint as a 500 carrying the compile error
 * (TQL-CAMEL-3103), and the watcher reports the failure and keeps watching.
 */
public final class RouteWatcher implements AutoCloseable {

    /** Registry name the runtime binds the (unstarted) watcher under. */
    static final String BEAN = "tesseraqlRouteWatcher";

    private static final Logger LOG = LoggerFactory.getLogger(RouteWatcher.class);
    private static final long QUIET_MILLIS = 300;

    private final Path appHome;
    private final RouteReloader reloader;
    private final Debounce debounce = new Debounce(QUIET_MILLIS, System::currentTimeMillis);
    private WatchService watchService;
    private Thread thread;

    RouteWatcher(Path appHome, RouteReloader reloader) {
        this.appHome = appHome.toAbsolutePath().normalize();
        this.reloader = reloader;
    }

    /**
     * Starts watching {@code web/} on a daemon thread. Each debounced reload reports one
     * concise line to {@code out} (plus one line per route that failed to compile); the
     * thread stops with {@link #close()} or when the JVM exits.
     */
    public synchronized void start(Consumer<String> out) {
        if (thread != null) {
            return;
        }
        // The reload's full scope (RouteReloader): the web/ routes plus everything that
        // bakes into them — shared definitions rebuild every route, a workflow change
        // rebuilds its synthesized transition routes, and templates/ joins for the view
        // documents that may live there (docs/view-composition.md wave 1; plain templates
        // resolve live at render time and their events are filtered out below). Only trees
        // that exist at start are watched; a surface added later still needs a restart.
        List<Path> roots = new ArrayList<>();
        for (String surface : List.of("web", "workflow", "decisions", "rules", "scope",
                "domains", "templates")) {
            Path root = appHome.resolve(surface);
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            out.accept("Watch: " + appHome.resolve("web")
                    + " does not exist; nothing to watch.");
            return;
        }
        try {
            watchService = appHome.getFileSystem().newWatchService();
            for (Path root : roots) {
                registerTree(root);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not watch " + appHome, ex);
        }
        thread = new Thread(() -> run(out), "tql-watch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void close() {
        Thread running = thread;
        thread = null;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ex) {
                LOG.debug("Closing the route watcher: {}", ex.getMessage());
            }
        }
        if (running != null) {
            running.interrupt();
            try {
                running.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(Consumer<String> out) {
        try {
            while (true) {
                // Block for the next event, or — with a batch pending — only until the quiet
                // period elapses, so the coalesced reload fires even when the burst stops.
                WatchKey key = debounce.hasPending()
                        ? watchService.poll(Math.max(1, debounce.millisUntilQuiet()),
                                TimeUnit.MILLISECONDS)
                        : watchService.take();
                if (key != null) {
                    Path dir = (Path) key.watchable();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        try {
                            accept(dir, event);
                        } catch (RuntimeException ex) {
                            // One unreadable event must not end the watch. Files.walk reports a
                            // directory that vanished mid-traversal as UncheckedIOException,
                            // which is not an IOException and so escaped accept()'s own catch,
                            // unwound this thread, and left --watch silently dead for the rest
                            // of the process (start() will not replace a thread that died).
                            LOG.warn("Ignoring watch event under {}: {}", dir, ex.toString());
                        }
                    }
                    key.reset();
                }
                List<String> batch = debounce.drainIfQuiet();
                if (!batch.isEmpty()) {
                    reload(batch, out);
                }
            }
        } catch (ClosedWatchServiceException | InterruptedException stopped) {
            LOG.debug("Route watcher stopped");
        }
    }

    private void accept(Path dir, WatchEvent<?> event) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            // Events were dropped; anything under this directory may have changed.
            debounce.offer(relative(dir));
            return;
        }
        Path child = dir.resolve((Path) event.context());
        if (isNoise(child.getFileName().toString())) {
            return;
        }
        // Under templates/ only view documents bake into routes; every other template
        // resolves live at render time, so its events would just be wasted reloads.
        if (child.startsWith(appHome.resolve("templates")) && !Files.isDirectory(child)
                && !child.getFileName().toString().endsWith(".view.yml")) {
            return;
        }
        if (Files.isDirectory(child)) {
            // A directory's own modify events (a child changed) are noise — the child's own
            // event carries the change — but a NEW directory must join the watch, and any
            // files that landed in it before registration are offered as changes.
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                try {
                    registerTree(child);
                    try (Stream<Path> files = Files.walk(child)) {
                        files.filter(Files::isRegularFile)
                                .filter(file -> !isNoise(file.getFileName().toString()))
                                .forEach(file -> debounce.offer(relative(file)));
                    }
                } catch (IOException ex) {
                    LOG.warn("Could not watch new directory {}: {}", child, ex.getMessage());
                }
            }
            return;
        }
        debounce.offer(relative(child));
    }

    private void reload(List<String> batch, Consumer<String> out) {
        String trigger = batch.get(0)
                + (batch.size() == 1 ? "" : " (+" + (batch.size() - 1) + " more)");
        RouteReloader.Result result;
        try {
            result = reloader.reload();
        } catch (Exception ex) {
            // A manifest that fails to load as a whole (malformed app.yml/config) or a
            // cross-app route conflict aborts the reload with nothing partial to apply. The
            // last good routes keep serving; report it and keep watching.
            out.accept("Watch: reload failed after " + trigger + " changed: "
                    + ex.getMessage());
            return;
        }
        out.accept("Watch: " + summary(result) + " after " + trigger + " changed");
        for (RouteReloader.RouteFailure failure : result.failed()) {
            out.accept("Watch: " + label(failure) + " failed to compile: " + failure.error()
                    + " (the endpoint serves this error as a 500 until the file is fixed)");
        }
    }

    private static String summary(RouteReloader.Result result) {
        List<String> parts = new ArrayList<>();
        if (!result.reloaded().isEmpty()) {
            parts.add(result.reloaded().size() + " changed");
        }
        if (!result.added().isEmpty()) {
            parts.add(result.added().size() + " added");
        }
        if (!result.removed().isEmpty()) {
            parts.add(result.removed().size() + " removed");
        }
        if (!result.failed().isEmpty()) {
            parts.add(result.failed().size() + " failed");
        }
        return parts.isEmpty()
                ? "no route changes"
                : "reloaded routes (" + String.join(", ", parts) + ")";
    }

    private static String label(RouteReloader.RouteFailure failure) {
        if (failure.id() == null) {
            // An unparseable document that never served has no route id; path is its source.
            return "route document " + failure.path();
        }
        return failure.method() + " " + failure.path() + " (" + failure.id() + ")";
    }

    /** Registers {@code root} and every non-hidden subdirectory with the watch service. */
    private void registerTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (!dir.equals(root) && isNoise(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String relative(Path path) {
        return appHome.relativize(path.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /**
     * Editor noise the watcher ignores: backup ({@code ~}), swap ({@code .swp}/{@code .swx}),
     * temp ({@code .tmp}) files, and hidden dotfiles (which also excludes dot-directories
     * from the watch).
     */
    static boolean isNoise(String fileName) {
        return fileName.startsWith(".") || fileName.endsWith("~") || fileName.endsWith(".swp")
                || fileName.endsWith(".swx") || fileName.endsWith(".tmp");
    }

    /**
     * Coalesces a burst of watch events into one deduplicated batch once no further event has
     * arrived for a quiet period — editors fire several events per save, and one save often
     * touches several files. Isolated from the file-system plumbing so it tests with a fake
     * clock.
     */
    static final class Debounce {

        private final long quietMillis;
        private final LongSupplier clock;
        private final Set<String> pending = new LinkedHashSet<>();
        private long lastOfferAt;

        Debounce(long quietMillis, LongSupplier clock) {
            this.quietMillis = quietMillis;
            this.clock = clock;
        }

        /** Adds a changed path to the batch; every offer restarts the quiet period. */
        synchronized void offer(String path) {
            pending.add(path);
            lastOfferAt = clock.getAsLong();
        }

        synchronized boolean hasPending() {
            return !pending.isEmpty();
        }

        /** How long until the pending batch is quiet enough to drain (0 when none/elapsed). */
        synchronized long millisUntilQuiet() {
            return pending.isEmpty()
                    ? 0
                    : Math.max(0, lastOfferAt + quietMillis - clock.getAsLong());
        }

        /** The coalesced batch if the quiet period elapsed; empty (and kept) otherwise. */
        synchronized List<String> drainIfQuiet() {
            if (pending.isEmpty() || clock.getAsLong() - lastOfferAt < quietMillis) {
                return List.of();
            }
            List<String> batch = List.copyOf(pending);
            pending.clear();
            return batch;
        }
    }
}
