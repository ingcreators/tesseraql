package io.tesseraql.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a running application holds besides its pipelines: the services it bound, and the things it
 * has to stop (docs/camel-removal.md structural decision 2).
 *
 * <p>This is the last of Camel's jobs in this framework, and it was two: a registry keyed by name,
 * and a list of services started and stopped with the process. The measurement is what makes the
 * replacement small — of the 64 calls a step ever made on the Camel context, 63 were a lookup, and
 * the framework's own code used {@code addService}, {@code start} and {@code stop} and nothing
 * else. An engine was carried for a map and a list.
 *
 * <p><strong>Starting is ordered, stopping is reversed.</strong> A service that was started after
 * another may depend on it; the reverse order is the only one that lets it say goodbye first, and
 * it is what Camel's context did.
 */
public final class RuntimeContext implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(RuntimeContext.class);

    /** Something the runtime starts and stops with itself. */
    public interface Service {

        /**
         * Begins serving. Nothing by default: several of these are constructed running and
         * registered only so that something stops them, which is the half that cannot be skipped.
         */
        default void start() throws Exception {
        }

        void stop() throws Exception;
    }

    private final Map<String, Object> beans = new ConcurrentHashMap<>();
    private final List<Service> services = new ArrayList<>();
    private volatile boolean started;

    /** A view of this context's beans, for the steps that only look things up. */
    public Beans beans() {
        return new Beans() {
            @Override
            public <T> T lookup(String name, Class<T> type) {
                return RuntimeContext.this.lookup(name, type);
            }
        };
    }

    /** Publishes a service or a value under {@code name}. */
    public void bind(String name, Object bean) {
        beans.put(name, bean);
    }

    /** Withdraws what was bound under {@code name}. */
    public void unbind(String name) {
        beans.remove(name);
    }

    /** The bean bound under {@code name}, or null when nothing is or it is another type. */
    public <T> T lookup(String name, Class<T> type) {
        Object bean = beans.get(name);
        return type.isInstance(bean) ? type.cast(bean) : null;
    }

    /** The single bean of {@code type}, or null when there is not exactly one. */
    public <T> T findSingleByType(Class<T> type) {
        T found = null;
        for (Object bean : beans.values()) {
            if (type.isInstance(bean)) {
                if (found != null) {
                    return null;
                }
                found = type.cast(bean);
            }
        }
        return found;
    }

    /**
     * Adds a service, starting it immediately when this context is already running.
     *
     * <p>The immediate start is what Camel's {@code addService} did and what several callers rely
     * on: a poll loop added while the runtime serves has to begin polling, not wait for a restart.
     */
    public void addService(Service service) throws Exception {
        synchronized (services) {
            services.add(service);
        }
        if (started) {
            service.start();
        }
    }

    /** Starts everything added so far, in the order it was added. */
    public void start() throws Exception {
        List<Service> starting;
        synchronized (services) {
            starting = List.copyOf(services);
        }
        for (Service service : starting) {
            service.start();
        }
        started = true;
    }

    /**
     * Stops everything, in reverse order, and does not let one failure strand the rest.
     *
     * <p>A stop that gave up halfway would leave threads running and connections open, which is
     * the opposite of what a stop is for.
     */
    @Override
    public void close() {
        started = false;
        List<Service> stopping;
        synchronized (services) {
            stopping = new ArrayList<>(services);
            services.clear();
        }
        for (int at = stopping.size() - 1; at >= 0; at--) {
            try {
                stopping.get(at).stop();
            } catch (Exception failed) {
                LOG.warn("A service did not stop cleanly", failed);
            }
        }
    }
}
