package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.AppUpgrader;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converges a running host to the install root's state (docs/runtime-replace.md structural
 * decision 2): {@code catalog.json} names each member's active version, {@code .upgrade/<name>.json}
 * names a staged candidate and its weight, and boot already builds its whole hosting arrangement
 * by reading them — a host restart mid-canary "works" because boot is a reconciliation. This
 * class makes that continuous: <b>a running host converges to the same function of the same
 * files that boot computes.</b> One protocol, two read points, no new channel.
 *
 * <p>Event-driven, one serialized thread: starting runtimes is heavy and blocking, and two
 * concurrent replaces of one member is a race nobody needs. Every pass reads the files fresh and
 * diffs against the live slots; every rule is idempotent, so duplicate watch events, a promote's
 * two file writes arriving as two events, and a pass racing a write all resolve to "read again,
 * diff again, nothing to do". <b>Failure does not loop</b>: a candidate that failed admission
 * stays failed, recorded in the status file, until the operator writes something new.
 *
 * <p><b>The host reports back through a file it alone writes</b> —
 * {@code .upgrade/<name>.status.json}, each attempt's outcome, applied or refused with the
 * refusal's own message. One file, one writer: the CLI writes intent, the host writes outcome,
 * and neither ever writes the other's file. Membership stays start-time on purpose: a new name
 * in the catalogue, or one removed, is the stack changing shape — a stack deploy — so the
 * reconciler logs the owed restart and touches nothing.
 */
final class StackReconciler implements AutoCloseable {

    /**
     * What a reconciliation drives: the host's replace operation set, behind an interface so the
     * diff rules are testable against a recording double while production wires
     * {@link MultiAppHost} straight in.
     */
    interface HostOperations {

        Set<String> appNames();

        InstalledApp entry(String appName);

        InstalledApp canaryEntry(String appName);

        boolean hasCanary(String appName);

        int canaryWeight(String appName);

        void replace(InstalledApp entry);

        void stageCanary(InstalledApp entry, int weightPercent);

        void setCanaryWeight(String appName, int weightPercent);

        void promoteCanary(String appName);

        void discardCanary(String appName);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackReconciler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Coalesces the burst a single deploy writes (catalogue + state) into one pass. */
    private static final long DEBOUNCE_MILLIS = 200;

    private final Path installRoot;
    private final HostOperations host;
    private final AppUpgrader upgrader = new AppUpgrader();
    private final ScheduledExecutorService passes = Executors
            .newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tesseraql-stack-reconciler");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean passPending = new AtomicBoolean();
    /** Membership edits are logged once, not per pass — the answer never changes while running. */
    private final Set<String> reportedMembershipEdits = ConcurrentHashMap.newKeySet();
    private final WatchService watcher;
    private final Thread watchThread;

    StackReconciler(Path installRoot, HostOperations host) {
        this.installRoot = installRoot;
        this.host = host;
        try {
            Files.createDirectories(installRoot.resolve(".upgrade"));
            this.watcher = installRoot.getFileSystem().newWatchService();
            installRoot.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            installRoot.resolve(".upgrade").register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException ex) {
            passes.shutdownNow();
            throw new java.io.UncheckedIOException(
                    "Could not watch the install root for deploys: " + installRoot, ex);
        }
        this.watchThread = new Thread(this::watch, "tesseraql-stack-reconciler-watch");
        watchThread.setDaemon(true);
        watchThread.start();
        // One pass at start: boot already reconciled, and this closes the window between boot's
        // read and the watch registration — idempotence makes it a no-op when nothing landed.
        requestPass();
    }

    private void watch() {
        while (true) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException stopped) {
                return;
            }
            boolean relevant = false;
            for (java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                String name = String.valueOf(event.context());
                // The intent files only: the catalogue, and the state files. The status files
                // this class itself writes into the same directory must not re-trigger it, and
                // temp files from the atomic writes are noise.
                if ("catalog.json".equals(name)
                        || (name.endsWith(".json") && !name.endsWith(".status.json"))) {
                    relevant = true;
                }
            }
            key.reset();
            if (relevant) {
                requestPass();
            }
        }
    }

    /** Schedules one debounced pass; further requests before it runs fold into it. */
    private void requestPass() {
        if (passPending.compareAndSet(false, true)) {
            try {
                passes.schedule(this::runPass, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException closed) {
                // A closed reconciler schedules nothing; the next start's boot reconciles.
                passPending.set(false);
            }
        }
    }

    private void runPass() {
        passPending.set(false);
        try {
            reconcileOnce();
        } catch (RuntimeException unexpected) {
            LOG.warn("Reconciliation pass failed; waiting for the next event: {}",
                    unexpected.getMessage());
        }
    }

    /**
     * One full diff-and-act pass over the members. Package-private so the rules are testable
     * synchronously; the watcher drives exactly this.
     */
    void reconcileOnce() {
        AppCatalog catalog;
        try {
            catalog = new AppCatalog(installRoot);
        } catch (RuntimeException torn) {
            // The last line of defence under the atomic writes: a malformed read logs and waits
            // for the next event, acting on nothing.
            LOG.warn("Could not read the catalogue; skipping this pass: {}", torn.getMessage());
            return;
        }
        for (InstalledApp catalogued : catalog.list()) {
            if (!host.appNames().contains(catalogued.name())
                    && reportedMembershipEdits.add(catalogued.name())) {
                LOG.warn("The catalogue holds '{}', which this stack was not started with."
                        + " Membership is start-time: adding an application is a stack deploy,"
                        + " so it starts at the next stack start.", catalogued.name());
            }
        }
        for (String name : host.appNames()) {
            reconcileMember(name, catalog);
        }
    }

    private void reconcileMember(String name, AppCatalog catalog) {
        Optional<InstalledApp> catalogued = catalog.find(name);
        if (catalogued.isEmpty()) {
            if (reportedMembershipEdits.add(name)) {
                LOG.warn("'{}' left the catalogue while hosted. Membership is start-time:"
                        + " removing an application is a stack deploy, so it keeps serving"
                        + " until the next stack start.", name);
            }
            return;
        }
        Optional<AppUpgrader.CanaryStatus> staged;
        try {
            staged = upgrader.canary(name, installRoot);
        } catch (RuntimeException torn) {
            LOG.warn("Could not read '{}' upgrade state; skipping it this pass: {}", name,
                    torn.getMessage());
            return;
        }
        InstalledApp stable = host.entry(name);
        InstalledApp canary = host.canaryEntry(name);
        try {
            if (!catalogued.get().version().equals(stable.version())) {
                // The catalogue moved. To the staged candidate's version = what a promote on
                // disk looks like (the candidate has been serving its share — nothing starts);
                // anywhere else = a direct upgrade, or a rollback, which is just the catalogue
                // moving back onto files still on disk.
                if (canary != null
                        && canary.version().equals(catalogued.get().version())) {
                    host.promoteCanary(name);
                    applied(name, "promote", catalogued.get().version());
                } else {
                    host.replace(catalogued.get());
                    applied(name, "replace", catalogued.get().version());
                }
                // The pass acted; converge the rest (a rollback with a candidate still staged,
                // a weight written with the promote) on a follow-up pass rather than acting
                // twice on one read.
                requestPass();
                return;
            }
            if (staged.isPresent()) {
                InstalledApp candidate = staged.get().candidate();
                int weight = staged.get().weightPercent();
                if (canary == null) {
                    host.stageCanary(candidate, weight);
                    applied(name, "stage", candidate.version());
                } else if (!canary.version().equals(candidate.version())) {
                    // The operator staged a different candidate over a running one: converge by
                    // retiring the runtime whose files no longer name it, then staging the one
                    // that does.
                    host.discardCanary(name);
                    host.stageCanary(candidate, weight);
                    applied(name, "stage", candidate.version());
                } else if (host.canaryWeight(name) != weight) {
                    host.setCanaryWeight(name, weight);
                    applied(name, "weight " + weight, candidate.version());
                }
            } else if (canary != null) {
                host.discardCanary(name);
                applied(name, "discard", canary.version());
            }
        } catch (RuntimeException refused) {
            LOG.warn("Deploy of '{}' refused; the serving runtime is untouched: {}", name,
                    refused.getMessage());
            refused(name, refused.getMessage());
        }
    }

    /** The host's report of one attempt; see the class javadoc for the one-file-one-writer rule. */
    record Status(String name, String action, String version, String outcome, String message,
            String at) {
    }

    private void applied(String name, String action, String version) {
        LOG.info("Deploy of '{}' applied: {} v{}", name, action, version);
        writeStatus(new Status(name, action, version, "applied", null,
                java.time.Instant.now().toString()));
    }

    private void refused(String name, String message) {
        writeStatus(new Status(name, null, null, "refused", message,
                java.time.Instant.now().toString()));
    }

    private void writeStatus(Status status) {
        try {
            Path dir = installRoot.resolve(".upgrade");
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, status.name(), ".tmp");
            Files.write(temp, MAPPER.writeValueAsBytes(status));
            Files.move(temp, dir.resolve(status.name() + ".status.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unwritable) {
            // The outcome still happened and is in the log; a status file that cannot be
            // written must not fail the deploy it reports on.
            LOG.warn("Could not write the deploy status for '{}': {}", status.name(),
                    unwritable.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            watcher.close();
        } catch (IOException ignored) {
            // Closing the watch service is what stops the watch thread; nothing to add.
        }
        passes.shutdownNow();
    }
}
