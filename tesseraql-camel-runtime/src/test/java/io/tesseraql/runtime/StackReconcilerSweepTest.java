package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reconcile sweep (docs/hosting.md "A stack on more than one node"): a host converges to the
 * install root even when no filesystem event ever arrives.
 *
 * <p>That is the whole of the shared-install-root topology. A watch service reports what its own
 * host did to the directory, so on a share the writer — another node — is invisible to it; without
 * a sweep the other nodes keep serving the old version, silently. The tests below take the watch
 * out of the picture rather than simulating a network mount: a same-host write would fire the watch
 * and prove nothing about the case that matters.
 */
class StackReconcilerSweepTest {

    /** A host whose stable slot is a map, so convergence is observable without a runtime. */
    private static final class FakeHost implements StackReconciler.HostOperations {

        final Map<String, InstalledApp> stable = new HashMap<>();
        final List<String> operations = new ArrayList<>();

        @Override
        public Set<String> appNames() {
            return stable.keySet();
        }

        @Override
        public InstalledApp entry(String appName) {
            return stable.get(appName);
        }

        @Override
        public InstalledApp canaryEntry(String appName) {
            return null;
        }

        @Override
        public boolean hasCanary(String appName) {
            return false;
        }

        @Override
        public int canaryWeight(String appName) {
            return 0;
        }

        @Override
        public void replace(InstalledApp entry) {
            stable.put(entry.name(), entry);
            operations.add("replace " + entry.name() + " " + entry.version());
        }

        @Override
        public void stageCanary(InstalledApp entry, int weightPercent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCanaryWeight(String appName, int weightPercent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void promoteCanary(String appName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void discardCanary(String appName) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * The case a shared install root lives on: another node rewrote the catalogue, this node was
     * told nothing, and the sweep converges it anyway.
     */
    @Test
    void aCatalogueChangeNoEventAnnouncedStillConverges(@TempDir Path installRoot)
            throws IOException, InterruptedException {
        FakeHost host = new FakeHost();
        host.stable.put("shop", entry("1.0.0"));
        AppCatalog catalog = new AppCatalog(installRoot);
        catalog.register(entry("1.0.0"));

        try (StackReconciler sweeping = new StackReconciler(installRoot, host,
                Duration.ofMillis(200))) {
            assertThat(sweeping).isNotNull();
            // Written the way another node's deploy leaves it: this host's watch service has no
            // reason to report it, and the test never asks the reconciler to look.
            catalog.replace(entry("2.0.0"));

            for (int attempt = 0; attempt < 100; attempt++) {
                if ("2.0.0".equals(host.stable.get("shop").version())) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(host.stable.get("shop").version()).isEqualTo("2.0.0");
            assertThat(host.operations).contains("replace shop 2.0.0");
        }
    }

    /** An idle sweep is a read and a diff: it must not act, and must not keep acting. */
    @Test
    void anIdleSweepDoesNothing(@TempDir Path installRoot)
            throws IOException, InterruptedException {
        FakeHost host = new FakeHost();
        host.stable.put("shop", entry("1.0.0"));
        new AppCatalog(installRoot).register(entry("1.0.0"));

        try (StackReconciler sweeping = new StackReconciler(installRoot, host,
                Duration.ofMillis(200))) {
            assertThat(sweeping).isNotNull();
            Thread.sleep(1_200);
            assertThat(host.operations).isEmpty();
        }
    }

    /**
     * A directory that can be neither watched nor swept leaves nothing to converge with, so the
     * stack refuses rather than running blind. With the sweep on, an unwatchable directory is only
     * a warning — that combination is the network mount the topology exists for.
     */
    @Test
    void anUnwatchableRootWithoutASweepRefuses(@TempDir Path parent) throws IOException {
        Path missing = parent.resolve("not-a-directory");
        Files.writeString(missing, "this is a file, and a file cannot be watched\n");

        assertThatThrownBy(() -> new StackReconciler(missing, new FakeHost(), Duration.ZERO))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("sweep is disabled");
    }

    private static InstalledApp entry(String version) {
        return new InstalledApp("shop", version, "shop/" + version, List.of());
    }
}
