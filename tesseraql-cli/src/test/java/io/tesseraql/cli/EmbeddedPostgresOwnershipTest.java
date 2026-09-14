package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A stop and a start on one {@link EmbeddedPostgresSupport.Ownership} never end with a server
 * nobody stops, whichever arrives first and however far the start has got.
 *
 * <p>The window {@code EmbeddedDbShutdownIntegrationTest} reaches with a real signal is timed:
 * it lands the interrupt after the library has logged "postmaster started as", and what it finds
 * there depends on the runner. The failing runs (CI, 2026-09-14, three of them, one on main)
 * read the same way: the stop looked for a {@code postgres} process at that instant, found none,
 * and returned, because the process the log names is {@code pg_ctl}, still on its way to
 * forking the shell that execs the server. These cases take the timing out: a stop that arrives
 * while the start is in progress is placed at a moment nothing can be found by hand yet, and
 * must still end with the server stopped. In-process, no signal: the claim is the ownership's
 * own, and the hook is only one caller of it.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class EmbeddedPostgresOwnershipTest {

    /**
     * The window. The stop arrives once {@code initdb} has begun writing the directory (its
     * {@code PG_VERSION} is the first file it leaves), which is before any postmaster exists — a
     * stop that looks for one by hand at this moment finds nothing, and the start, which keeps
     * running on its own thread exactly as it does under a JVM's shutdown hooks, then brings the
     * server up behind it. The stop must wait for the start to conclude and stop what it made.
     */
    @Test
    void aStopThatArrivesWhileTheStartIsInProgressStopsWhatTheStartProduces(@TempDir Path dir)
            throws Exception {
        EmbeddedPostgresSupport.Ownership ownership = new EmbeddedPostgresSupport.Ownership();
        Path data = dir.resolve("pgdata");
        ExecutorService starter = Executors.newSingleThreadExecutor();
        EmbeddedPostgresSupport.Handle handle = null;
        try {
            Future<EmbeddedPostgresSupport.Handle> start = starter.submit(
                    () -> EmbeddedPostgresSupport.start(ownership, data, null, null, false));
            awaitInitdb(data, start);

            ownership.stop();

            handle = start.get(120, TimeUnit.SECONDS);
            assertThat(accepts(handle.port()))
                    .as("the start concluded with a server on port %d, and the stop that arrived"
                            + " while it was in progress stopped it", handle.port())
                    .isFalse();
        } finally {
            if (handle != null) {
                handle.close();
            }
            starter.shutdownNow();
        }
    }

    /**
     * The other order. A stop that arrives before the start has claimed a directory — the
     * interrupt landing while the binary is still being resolved — leaves the ownership with
     * nothing to stop, so the start that follows must not begin: a server that comes up after
     * the stop has been and gone is one nobody stops.
     */
    @Test
    void aStopThatArrivesBeforeTheStartClaimsAnythingRefusesTheStart(@TempDir Path dir)
            throws Exception {
        EmbeddedPostgresSupport.Ownership ownership = new EmbeddedPostgresSupport.Ownership();
        Path data = dir.resolve("pgdata");
        ownership.stop();

        EmbeddedPostgresSupport.Handle leaked = null;
        try {
            leaked = EmbeddedPostgresSupport.start(ownership, data, null, null, false);
            fail("the start began after the stop, and brought up a server on port %d that"
                    + " nothing will stop", leaked.port());
        } catch (IllegalStateException refused) {
            assertThat(refused).hasMessageContaining("stopping");
            assertThat(data.resolve("postmaster.pid")).doesNotExist();
        } finally {
            if (leaked != null) {
                leaked.close();
            }
        }
    }

    /** Waits until {@code initdb} has begun in {@code data}, or the start ended before it did. */
    private static void awaitInitdb(Path data, Future<?> start) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (!Files.exists(data.resolve("PG_VERSION"))) {
            if (start.isDone()) {
                start.get();
                fail("the start concluded before initdb wrote anything into " + data);
            }
            if (System.currentTimeMillis() > deadline) {
                fail("initdb did not begin in " + data);
            }
            Thread.sleep(10);
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }
}
