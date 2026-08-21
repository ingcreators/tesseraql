package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.opsui.PollSourceStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
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

    private final CamelContext context = new DefaultCamelContext();
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

    private void start(Processor importer, String include) throws Exception {
        loop = new PollLoop("orders.intake", "local", new LocalPollSource(inbound), importer,
                context, include, ".done", ".error", 200, null, status);
        loop.start();
    }

    /** An importer that records what it was handed, name and content. */
    private static Processor record(List<String> imported) {
        return exchange -> {
            String name = (String) exchange.getMessage().getHeader(Exchange.FILE_NAME);
            try (InputStream body = (InputStream) exchange.getMessage().getBody()) {
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
