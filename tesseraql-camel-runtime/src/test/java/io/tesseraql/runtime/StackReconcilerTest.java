package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reconciler's diff rules, against a recording host double (docs/runtime-replace.md
 * structural decision 2): each on-disk shape fires exactly the operation a boot would have
 * arrived at, every rule is idempotent, and a file that cannot be read skips the pass instead of
 * acting on garbage. The watcher is closed in every test — the rules are driven synchronously,
 * because what is under test is the diff, not filesystem event latency.
 */
class StackReconcilerTest {

    /** A host whose slots are maps: operations mutate them, so convergence is observable. */
    private static final class FakeHost implements StackReconciler.HostOperations {

        final Map<String, InstalledApp> stable = new HashMap<>();
        final Map<String, InstalledApp> canary = new HashMap<>();
        final Map<String, Integer> weights = new HashMap<>();
        final List<String> operations = new ArrayList<>();
        RuntimeException refusal;

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
            return canary.get(appName);
        }

        @Override
        public boolean hasCanary(String appName) {
            return canary.containsKey(appName);
        }

        @Override
        public int canaryWeight(String appName) {
            return weights.getOrDefault(appName, 0);
        }

        @Override
        public void replace(InstalledApp entry) {
            refuseIfArranged();
            operations.add("replace " + entry.version());
            stable.put(entry.name(), entry);
        }

        @Override
        public void stageCanary(InstalledApp entry, int weightPercent) {
            refuseIfArranged();
            operations.add("stage " + entry.version() + "@" + weightPercent);
            canary.put(entry.name(), entry);
            weights.put(entry.name(), weightPercent);
        }

        @Override
        public void setCanaryWeight(String appName, int weightPercent) {
            operations.add("weight " + weightPercent);
            weights.put(appName, weightPercent);
        }

        @Override
        public void promoteCanary(String appName) {
            operations.add("promote");
            stable.put(appName, canary.remove(appName));
            weights.remove(appName);
        }

        @Override
        public void discardCanary(String appName) {
            operations.add("discard");
            canary.remove(appName);
            weights.remove(appName);
        }

        private void refuseIfArranged() {
            if (refusal != null) {
                throw refusal;
            }
        }
    }

    private Path installRoot;
    private FakeHost host;
    private StackReconciler reconciler;

    @BeforeEach
    void setUp() throws IOException {
        installRoot = Files.createTempDirectory("tesseraql-reconciler-test");
        host = new FakeHost();
        host.stable.put("shop", entry("1.0.0"));
        new AppCatalog(installRoot).register(entry("1.0.0"));
        reconciler = new StackReconciler(installRoot, host);
        // The rules are exercised synchronously below; the watcher and its executor would race
        // the same fake, so they go first.
        reconciler.close();
        host.operations.clear();
    }

    @AfterEach
    void cleanUp() throws IOException {
        reconciler.close();
        try (Stream<Path> files = Files.walk(installRoot)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static InstalledApp entry(String version) {
        return new InstalledApp("shop", version, "shop/" + version, List.of());
    }

    @Test
    void aMovedCatalogueReplaces() {
        new AppCatalog(installRoot).replace(entry("2.0.0"));

        reconciler.reconcileOnce();

        assertThat(host.operations).containsExactly("replace 2.0.0");
        assertThat(host.stable.get("shop").version()).isEqualTo("2.0.0");
    }

    @Test
    void aCatalogueMovedOntoTheCanarySlotVersionPromotes() {
        host.canary.put("shop", entry("2.0.0"));
        host.weights.put("shop", 25);
        new AppCatalog(installRoot).replace(entry("2.0.0"));

        reconciler.reconcileOnce();

        assertThat(host.operations).containsExactly("promote");
        assertThat(host.stable.get("shop").version()).isEqualTo("2.0.0");
        assertThat(host.canary).isEmpty();
    }

    @Test
    void aStagedCandidateWithNoCanarySlotStages() throws IOException {
        stagedState("2.0.0", 15);

        reconciler.reconcileOnce();

        assertThat(host.operations).containsExactly("stage 2.0.0@15");
    }

    @Test
    void aMovedWeightReachesTheSlot() throws IOException {
        host.canary.put("shop", entry("2.0.0"));
        host.weights.put("shop", 10);
        stagedState("2.0.0", 60);

        reconciler.reconcileOnce();

        assertThat(host.operations).containsExactly("weight 60");
    }

    @Test
    void aCanarySlotWithNoStagedCandidateIsDiscarded() {
        host.canary.put("shop", entry("2.0.0"));
        host.weights.put("shop", 10);

        reconciler.reconcileOnce();

        assertThat(host.operations).containsExactly("discard");
        assertThat(host.canary).isEmpty();
    }

    @Test
    void aDifferentStagedCandidateRetiresTheRunningOneAndStagesIt() throws IOException {
        host.canary.put("shop", entry("2.0.0"));
        host.weights.put("shop", 10);
        stagedState("3.0.0", 10);

        reconciler.reconcileOnce();

        assertThat(host.operations).containsExactly("discard", "stage 3.0.0@10");
        assertThat(host.canary.get("shop").version()).isEqualTo("3.0.0");
    }

    /** Every rule converges: a second pass over the same files does nothing. */
    @Test
    void aConvergedPassIsANoOp() throws IOException {
        new AppCatalog(installRoot).replace(entry("2.0.0"));
        reconciler.reconcileOnce();
        host.operations.clear();

        reconciler.reconcileOnce();
        reconciler.reconcileOnce();

        assertThat(host.operations).isEmpty();
    }

    @Test
    void aMalformedCatalogueSkipsThePassActingOnNothing() throws IOException {
        Files.writeString(installRoot.resolve("catalog.json"), "{torn");

        reconciler.reconcileOnce();

        assertThat(host.operations).isEmpty();
    }

    @Test
    void aMalformedStateFileSkipsThatMemberOnly() throws IOException {
        Files.createDirectories(installRoot.resolve(".upgrade"));
        Files.writeString(installRoot.resolve(".upgrade/shop.json"), "{torn");

        reconciler.reconcileOnce();

        assertThat(host.operations).isEmpty();
    }

    /** Membership is start-time: a catalogue name the host was not started with does nothing. */
    @Test
    void aNewCatalogueNameStartsNothing() {
        new AppCatalog(installRoot)
                .register(new InstalledApp("other", "1.0.0", "other/1.0.0", List.of()));

        reconciler.reconcileOnce();

        assertThat(host.operations).isEmpty();
        assertThat(host.stable.keySet()).containsExactly("shop");
    }

    /** ...and a member that left the catalogue keeps serving. */
    @Test
    void aRemovedCatalogueEntryStopsNothing() throws IOException {
        Files.writeString(installRoot.resolve("catalog.json"), "[]");

        reconciler.reconcileOnce();

        assertThat(host.operations).isEmpty();
        assertThat(host.stable.get("shop").version()).isEqualTo("1.0.0");
    }

    @Test
    void anAppliedActionIsRecordedInTheStatusFile() throws IOException {
        new AppCatalog(installRoot).replace(entry("2.0.0"));

        reconciler.reconcileOnce();

        String status = Files.readString(installRoot.resolve(".upgrade/shop.status.json"));
        assertThat(status).contains("\"applied\"").contains("2.0.0").contains("replace");
    }

    /** Failure does not loop: the refusal is recorded and the host's state is untouched. */
    @Test
    void aRefusedActionIsRecordedWithItsOwnMessage() throws IOException {
        host.refusal = new IllegalStateException("modules unresolved: run tesseraql modules"
                + " resolve");
        new AppCatalog(installRoot).replace(entry("2.0.0"));

        reconciler.reconcileOnce();

        assertThat(host.stable.get("shop").version()).isEqualTo("1.0.0");
        String status = Files.readString(installRoot.resolve(".upgrade/shop.status.json"));
        assertThat(status).contains("\"refused\"").contains("modules unresolved");
    }

    private void stagedState(String candidateVersion, int weight) throws IOException {
        Files.createDirectories(installRoot.resolve(".upgrade"));
        Files.writeString(installRoot.resolve(".upgrade/shop.json"), """
                {"previous":null,"candidate":{"name":"shop","version":"%s",\
                "path":"shop/%s","entitledTenants":[]},"canaryWeight":%d}"""
                .formatted(candidateVersion, candidateVersion, weight));
    }
}
