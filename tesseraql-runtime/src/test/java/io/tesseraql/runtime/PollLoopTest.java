package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.opsui.PollSourceStatus;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.Step;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The poll cycle's rules, without a route and without a server (docs/camel-removal.md slice 1).
 *
 * <p>Each of these was an endpoint option — {@code antInclude}, {@code readLock=changed},
 * {@code move}, {@code moveFailed} — and the reason to test them here rather than only through the
 * container suites is that a rule stated in a query string was never checkable at all: what the
 * option meant was in another project's bytecode.
 */
class PollLoopTest {

    @TempDir
    Path inbound;

    private final RuntimeContext context = new RuntimeContext();
    private final PollSourceStatus status = new PollSourceStatus();
    private PollLoop loop;

    @AfterEach
    void stopLoop() throws Exception {
        if (loop != null) {
            loop.stop();
        }
        context.close();
    }

    @Test
    void aStableFileIsImportedAndMovedToTheDoneDirectory() throws Exception {
        Files.writeString(inbound.resolve("orders.csv"), "orderNo\nA-1\n");
        List<String> imported = new CopyOnWriteArrayList<>();

        start(record(imported), "*.csv");

        await(() -> Files.exists(inbound.resolve(".done/orders.csv")));
        assertThat(imported).containsExactly("orders.csv:orderNo\nA-1\n");
        assertThat(Files.exists(inbound.resolve("orders.csv"))).isFalse();
    }

    /**
     * A file whose import threw belongs in the failure directory.
     *
     * <p>The distinction is the whole reason the cycle waits for the import instead of handing it
     * to an executor: an operator reconciling by directory reads {@code .done} as "ingested".
     */
    @Test
    void aFileWhoseImportFailsIsMovedToTheFailureDirectory() throws Exception {
        Files.writeString(inbound.resolve("broken.csv"), "orderNo\nB-1\n");

        start(exchange -> {
            throw new IllegalStateException("no");
        }, "*.csv");

        await(() -> Files.exists(inbound.resolve(".error/broken.csv")));
        assertThat(Files.exists(inbound.resolve(".done/broken.csv"))).isFalse();
    }

    @Test
    void aFileTheIncludeGlobDoesNotAdmitIsLeftAlone() throws Exception {
        Files.writeString(inbound.resolve("orders.csv"), "orderNo\nA-1\n");
        Files.writeString(inbound.resolve("notes.txt"), "ignore me");
        List<String> imported = new CopyOnWriteArrayList<>();

        start(record(imported), "*.csv");

        await(() -> Files.exists(inbound.resolve(".done/orders.csv")));
        assertThat(Files.exists(inbound.resolve("notes.txt"))).isTrue();
        assertThat(imported).hasSize(1);
    }

    /**
     * An {@code include:} is a glob and nothing else now.
     *
     * <p>It used to be interpolated into a query string, where an {@code &} split the query and
     * bound whatever followed as further consumer options — which is why it was wrapped in
     * {@code RAW(...)}. With no URI there is nothing to smuggle into: the value matches a file
     * name that actually contains that character, and no file here does.
     */
    @Test
    void anIncludeGlobIsMatchedLiterallyAndCannotBindOptions() throws Exception {
        Files.writeString(inbound.resolve("orders.csv"), "orderNo\nA-1\n");
        List<String> imported = new CopyOnWriteArrayList<>();

        start(record(imported), "*.csv&noop=true");

        // Two cycles' worth of time: enough for the file to have been taken had the glob been
        // read as "*.csv" plus options.
        Thread.sleep(600);
        assertThat(imported).isEmpty();
        assertThat(Files.exists(inbound.resolve("orders.csv"))).isTrue();
    }

    /**
     * A file that is still being written is left for a later cycle.
     *
     * <p>This is {@code readLock=changed}: the fingerprint is re-read after a wait, and a file
     * whose size or modification time moved in between is not offered to the import. Driven from a
     * source that keeps changing rather than from a real writer, so the rule is asserted rather
     * than raced against.
     */
    @Test
    void aFileWhoseFingerprintKeepsChangingIsNotConsumed() throws Exception {
        AtomicInteger imports = new AtomicInteger();
        GrowingSource growing = new GrowingSource();

        loop = new PollLoop("growing.job", "local", growing, exchange -> imports.incrementAndGet(),
                context, null, ".done", ".error", 100, null, status);
        loop.start();

        Thread.sleep(800);
        assertThat(growing.statCalls.get()).isPositive();
        assertThat(imports).hasValue(0);
    }

    /**
     * A {@code consumeOnce:} source claims a file before importing it, and skips what it loses.
     *
     * <p>Claiming before rather than remembering after is the whole mechanism: two replicas can
     * both pass a "have I seen this?" check and both import, and only the insert settles it. This
     * was pinned against the endpoint's {@code idempotentEager=true} option until the cycle stopped
     * having endpoints (docs/camel-removal.md decision 4).
     */
    @Test
    void aFileAnotherReplicaHasClaimedIsNotImported() throws Exception {
        Files.writeString(inbound.resolve("orders.csv"), "orderNo\nA-1\n");
        List<String> imported = new CopyOnWriteArrayList<>();
        List<String> claimed = new CopyOnWriteArrayList<>();

        loop = new PollLoop("orders.intake", "local", new LocalPollSource(inbound),
                record(imported), context, "*.csv", ".done", ".error", 200,
                (jobId, key) -> {
                    claimed.add(key);
                    return false;
                }, status);
        loop.start();

        await(() -> !claimed.isEmpty());
        Thread.sleep(400);
        assertThat(imported).isEmpty();
        assertThat(Files.exists(inbound.resolve("orders.csv"))).isTrue();
        // Name, size and modification time: a partner re-sending a file under a name it has used
        // before is suppressed only while the bytes are identical.
        assertThat(claimed.get(0)).startsWith("orders.csv-");
    }

    /**
     * A file the source lists under a name that is not a plain file name is skipped, not imported.
     *
     * <p>The name is the server's, on a remote transport whose identity is verified only when a
     * {@code knownHostsFile} is declared, so it is the one input to this loop an attacker chooses.
     * The rule lives here, with the transport-independent rules, rather than in one client.
     */
    @Test
    void aListedNameThatIsNotAPlainNameIsSkipped() throws Exception {
        HostileSource source = new HostileSource();
        List<String> imported = new CopyOnWriteArrayList<>();

        start(source, record(imported), null);

        await(() -> imported.size() == 1);
        // The two hostile entries are refused before the glob, before the stability re-read, and
        // before the fetch that would have written them; the cycle keeps going past them.
        assertThat(source.fetched).containsExactly("orders.csv");
        assertThat(source.archived).containsExactly("orders.csv");
        assertThat(imported).containsExactly("orders.csv:name,qty\nplain,1\n");
    }

    private void start(Step importer, String include) throws Exception {
        start(new LocalPollSource(inbound), importer, include);
    }

    private void start(PollSource source, Step importer, String include) throws Exception {
        loop = new PollLoop("orders.intake", "local", source, importer,
                context, include, ".done", ".error", 200, null, status);
        loop.start();
    }

    /** An importer that records what it was handed, name and content. */
    private static Step record(List<String> imported) {
        return exchange -> {
            String name = exchange.getProperty(
                    io.tesseraql.pipeline.TesseraqlProperties.POLLED_FILE_NAME, String.class);
            try (InputStream body = (InputStream) exchange.getBody()) {
                imported.add(name + ":" + new String(body.readAllBytes()));
            }
        };
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** A source that lists what a hostile or impersonated server would, beside a legitimate file. */
    private final class HostileSource implements PollSource {

        private final List<String> fetched = new CopyOnWriteArrayList<>();
        private final List<String> archived = new CopyOnWriteArrayList<>();

        @Override
        public List<PolledFile> list() {
            return List.of(
                    new PolledFile("../../../config/application.yml", 8, 1_000L),
                    new PolledFile("sub/nested.csv", 8, 1_000L),
                    new PolledFile("orders.csv", 8, 1_000L));
        }

        @Override
        public Optional<PolledFile> stat(String name) {
            return Optional.of(new PolledFile(name, 8, 1_000L));
        }

        @Override
        public Fetched fetch(PolledFile file) throws IOException {
            fetched.add(file.name());
            Path target = inbound.resolve("fetched-" + file.name());
            Files.createDirectories(target.getParent());
            Files.writeString(target, "name,qty\nplain,1\n");
            return new Fetched(target, true);
        }

        @Override
        public void archive(PolledFile file, String directory) {
            archived.add(file.name());
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }

    /** A source whose single file grows on every stat, so it is never stable. */
    private static final class GrowingSource implements PollSource {

        private final AtomicInteger size = new AtomicInteger(1);
        private final AtomicInteger statCalls = new AtomicInteger();

        @Override
        public List<PolledFile> list() {
            return List.of(new PolledFile("growing.csv", size.get(), 1_000L));
        }

        @Override
        public Optional<PolledFile> stat(String name) {
            statCalls.incrementAndGet();
            return Optional.of(new PolledFile(name, size.incrementAndGet(), 1_000L));
        }

        @Override
        public Fetched fetch(PolledFile file) throws IOException {
            throw new IOException("a growing file must never be fetched");
        }

        @Override
        public void archive(PolledFile file, String directory) throws IOException {
            throw new IOException("a growing file must never be archived");
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }
}
