package io.tesseraql.runtime;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * A start that must know its port before it binds — a redirect URI, an external origin, a port
 * the configuration names — so the port is picked, released, and bound a moment later.
 *
 * <p>Another process can take it in that moment. A picked port comes from the ephemeral range
 * every outbound connection on the machine also draws from, and several sessions building side by
 * side widen the window (docs/host-development.md decision 8). This repeats pick-then-start, up
 * to {@link #PICKS} picks, when the start fails because the address is in use — and on nothing
 * else, so a boot that fails for its own reason fails at once.
 *
 * <p>Everything that can boot on port 0 does, and reads its port back; a test uses this only when
 * it cannot. A test whose assertion is the number itself picks it by hand and says why
 * (docs/build.md, "Ports in tests").
 */
final class PickedPort {

    static final int PICKS = 3;

    /** What starts on the port it is given — configuration written for it, then the bind. */
    @FunctionalInterface
    interface Start<T> {
        T on(int port) throws Exception;
    }

    /** Where picks come from; the tests of this class hand it ports they already hold. */
    @FunctionalInterface
    interface Picks {
        int next() throws IOException;
    }

    private PickedPort() {
    }

    static <T> T start(Start<T> start) throws Exception {
        return start(start, PickedPort::free);
    }

    static <T> T start(Start<T> start, Picks picks) throws Exception {
        List<Integer> lost = new ArrayList<>();
        Exception last = null;
        for (int i = 0; i < PICKS; i++) {
            int port = picks.next();
            try {
                return start.on(port);
            } catch (Exception failed) {
                if (!addressInUse(failed)) {
                    throw failed;
                }
                lost.add(port);
                last = failed;
            }
        }
        throw new IllegalStateException("Every picked port was taken before the start could bind"
                + " it: " + lost, last);
    }

    /** The bind refusal, wherever in the cause chain the starting code wrapped it. */
    static boolean addressInUse(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindException || String.valueOf(cause.getMessage())
                    .contains("Address already in use")) {
                return true;
            }
        }
        return false;
    }

    private static int free() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
