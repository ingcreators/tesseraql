package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

/**
 * The listen loop's connection lifecycle. The drain the loop performs reaches the event store,
 * which wraps every {@link java.sql.SQLException} in a {@link TqlException} — an unchecked
 * exception — so the reconnect path has to release the dedicated LISTEN connection on that route
 * as well as on the checked one. It did not, and since the listener borrows from the app's main
 * pool, a persistent drain failure exhausted the pool every component shares.
 */
class PgNotifyListenerTest {

    private static final TqlErrorCode BOOM = new TqlErrorCode(TqlDomain.BATCH, 5312);

    @Test
    void aFailingDrainReleasesTheListenConnection() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        PgNotifyListener listener = new PgNotifyListener(
                countingDataSource(opened, closed), drainThatThrows(), 50);

        listener.start();
        try {
            // The first attempt opens a connection, LISTENs, then throws out of the catch-up
            // drain. The connection must come back before the loop settles into its reconnect
            // sleep — asserting here, mid-sleep, is what distinguishes a released connection
            // from one that only doStop() would eventually close.
            waitUntil(() -> opened.get() >= 1);
            waitUntil(() -> closed.get() >= 1);

            assertThat(closed.get()).isEqualTo(opened.get());
        } finally {
            listener.stop();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 3s");
            }
            Thread.sleep(10);
        }
    }

    /** A consumer whose drain fails the way the JDBC store fails: an unchecked TqlException. */
    private static QueueConsumer drainThatThrows() {
        EventChannelStore store = (EventChannelStore) Proxy.newProxyInstance(
                EventChannelStore.class.getClassLoader(),
                new Class<?>[]{EventChannelStore.class},
                (proxy, method, args) -> {
                    if ("claim".equals(method.getName())) {
                        throw new TqlException(BOOM, "Failed to claim events");
                    }
                    return method.getReturnType() == List.class ? List.<EventMessage>of() : null;
                });
        return new QueueConsumer(new DefaultCamelContext(), store,
                List.of(new QueueConsumer.Subscription("orders", null, "queue.orders")), 3);
    }

    /**
     * Counts borrows and returns. The listen loop only calls setAutoCommit, createStatement and
     * close before the drain throws, so a proxy covering those is enough — it never reaches the
     * {@code unwrap(PGConnection)} that would need a real driver.
     */
    private static DataSource countingDataSource(AtomicInteger opened, AtomicInteger closed) {
        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> "execute".equals(method.getName()) ? Boolean.FALSE : null);
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (dsProxy, dsMethod, dsArgs) -> {
                    if (!"getConnection".equals(dsMethod.getName())) {
                        return null;
                    }
                    opened.incrementAndGet();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "createStatement" -> statement;
                                case "close" -> {
                                    closed.incrementAndGet();
                                    yield null;
                                }
                                case "isClosed" -> Boolean.FALSE;
                                default -> null;
                            });
                });
    }
}
