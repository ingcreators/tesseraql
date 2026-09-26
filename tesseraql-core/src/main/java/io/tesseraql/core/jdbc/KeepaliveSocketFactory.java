package io.tesseraql.core.jdbc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;
import jdk.net.ExtendedSocketOptions;

/**
 * The socket factory TesseraQL names to the PostgreSQL driver (docs/connection-liveness.md
 * decision 2): each socket runs TCP keepalive with TesseraQL's own timings, so a statement whose
 * database host has gone fails in about a minute instead of waiting on a read forever.
 *
 * <p>After {@value #IDLE_SECONDS} s with no traffic the kernel probes the peer, and after
 * {@value #COUNT} unanswered probes {@value #INTERVAL_SECONDS} s apart it resets the socket; the
 * blocked read then throws and the pool evicts the connection. The database host's kernel answers
 * the probes while the backend computes, so a statement that is long and silent but alive is never
 * cut — which is why this, and not a socket timeout.
 *
 * <p>The timings are per socket, so they need no operating system tuning. Where the JDK cannot
 * set them on the platform, the socket keeps alive with the operating system's timings, and one
 * warning says so. The driver sets {@code SO_KEEPALIVE} from its own {@code tcpKeepAlive} after
 * this factory returns, so {@link ConnectionProperties} turns that on too.
 *
 * <p>These are TesseraQL's timings for every driver that lets them be set: Oracle's and MariaDB's
 * take the same three numbers as connection properties ({@link ConnectionProperties}).
 */
public final class KeepaliveSocketFactory extends SocketFactory {

    /** Seconds without traffic before the first probe. */
    public static final int IDLE_SECONDS = 30;

    /** Seconds between unanswered probes. */
    public static final int INTERVAL_SECONDS = 10;

    /** Unanswered probes before the kernel resets the socket. */
    public static final int COUNT = 3;

    private static final System.Logger LOG = System
            .getLogger(KeepaliveSocketFactory.class.getName());

    private static final Set<java.net.SocketOption<?>> TIMINGS = Set.of(
            ExtendedSocketOptions.TCP_KEEPIDLE, ExtendedSocketOptions.TCP_KEEPINTERVAL,
            ExtendedSocketOptions.TCP_KEEPCOUNT);

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** The driver instantiates the factory by name, with no arguments. */
    public KeepaliveSocketFactory() {
        // Stateless: every socket gets the same timings.
    }

    /** The one the driver calls: an unconnected socket, which it then connects itself. */
    @Override
    public Socket createSocket() throws IOException {
        return keptAlive(new Socket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return keptAlive(new Socket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return keptAlive(new Socket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return keptAlive(new Socket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
            int localPort) throws IOException {
        return keptAlive(new Socket(address, port, localAddress, localPort));
    }

    /** {@code socket}, keeping alive with TesseraQL's timings where the platform allows them. */
    static Socket keptAlive(Socket socket) throws IOException {
        try {
            socket.setKeepAlive(true);
            if (socket.supportedOptions().containsAll(TIMINGS)) {
                socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, IDLE_SECONDS);
                socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL, INTERVAL_SECONDS);
                socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT, COUNT);
            } else if (WARNED.compareAndSet(false, true)) {
                LOG.log(System.Logger.Level.WARNING, "This platform ({0}) does not let the JDK"
                        + " set TCP keepalive timings per socket, so PostgreSQL connections keep"
                        + " alive with the operating system's timings: a vanished database host"
                        + " is noticed only as fast as those allow (docs/connection-liveness.md).",
                        System.getProperty("os.name"));
            }
            return socket;
        } catch (IOException | RuntimeException failure) {
            socket.close();
            throw failure;
        }
    }
}
