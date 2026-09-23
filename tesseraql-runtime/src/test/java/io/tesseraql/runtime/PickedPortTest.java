package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The pick-then-start retry (docs/host-development.md decision 8): a port another process took
 * between the pick and the bind costs one more pick, and a start that fails for any other reason
 * fails at once.
 */
class PickedPortTest {

    @Test
    void aPortTakenBeforeTheBindCostsOneMorePick() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            // The second pick is 0, which the socket turns into a port nobody holds — so this
            // case has no window of its own.
            Deque<Integer> picks = new ArrayDeque<>(List.of(taken.getLocalPort(), 0));
            AtomicInteger starts = new AtomicInteger();

            int bound = PickedPort.start(port -> {
                starts.incrementAndGet();
                return bindAndRelease(port);
            }, picks::pop);

            assertThat(starts).hasValue(2);
            assertThat(bound).isPositive().isNotEqualTo(taken.getLocalPort());
        }
    }

    @Test
    void threeTakenPortsFailNamingEachOfThem() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            int port = taken.getLocalPort();

            assertThatThrownBy(() -> PickedPort.start(PickedPortTest::bindAndRelease, () -> port))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[" + port + ", " + port + ", " + port + "]");
        }
    }

    @Test
    void aStartThatFailsForItsOwnReasonIsNotRetried() {
        AtomicInteger starts = new AtomicInteger();

        assertThatThrownBy(() -> PickedPort.start(port -> {
            starts.incrementAndGet();
            throw new IllegalStateException("the boot's own refusal");
        }, () -> 1)).hasMessage("the boot's own refusal");
        assertThat(starts).hasValue(1);
    }

    /** Binds the way a runtime does, wrapping the refusal as a boot failure would. */
    private static int bindAndRelease(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.getLocalPort();
        } catch (IOException refused) {
            throw new IllegalStateException("Could not start on port " + port, refused);
        }
    }
}
