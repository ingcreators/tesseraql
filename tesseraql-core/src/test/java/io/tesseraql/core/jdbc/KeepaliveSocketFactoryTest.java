package io.tesseraql.core.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.util.Set;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.Test;

/**
 * The factory's sockets keep alive with TesseraQL's timings (docs/connection-liveness.md decision
 * 2): 30 s idle, then three probes 10 s apart — or, where the platform does not let the JDK set
 * them, keepalive with the operating system's timings. Both branches are asserted, so the test
 * holds on every platform the build runs on.
 */
class KeepaliveSocketFactoryTest {

    @Test
    void theDriversUnconnectedSocketKeepsAliveWithTesseraqlsTimings() throws Exception {
        try (Socket socket = new KeepaliveSocketFactory().createSocket()) {
            assertThat(socket.isConnected()).as("the driver connects it itself").isFalse();
            assertThat(socket.getKeepAlive()).isTrue();
            if (supportsTimings(socket)) {
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE))
                        .isEqualTo(KeepaliveSocketFactory.IDLE_SECONDS);
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL))
                        .isEqualTo(KeepaliveSocketFactory.INTERVAL_SECONDS);
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT))
                        .isEqualTo(KeepaliveSocketFactory.COUNT);
            }
        }
    }

    @Test
    void aboutAMinuteToNoticeAVanishedPeer() {
        assertThat(KeepaliveSocketFactory.IDLE_SECONDS
                + KeepaliveSocketFactory.INTERVAL_SECONDS * KeepaliveSocketFactory.COUNT)
                .isEqualTo(60);
    }

    private static boolean supportsTimings(Socket socket) {
        return socket.supportedOptions().containsAll(Set.of(ExtendedSocketOptions.TCP_KEEPIDLE,
                ExtendedSocketOptions.TCP_KEEPINTERVAL, ExtendedSocketOptions.TCP_KEEPCOUNT));
    }
}
