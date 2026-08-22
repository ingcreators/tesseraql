package io.tesseraql.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where each HTTP surface answers, declared as data rather than as a REST DSL definition
 * (docs/http-edge.md decision 1).
 *
 * <p>Every framework and application route used to say this by calling
 * {@code rest().get(path).to("direct:id")}, which asked Camel to create a consumer as a side
 * effect of recording a URL. The runtime serves those routes itself now, so the consumer was the
 * only part still being asked for — and asking for it kept {@code camel-platform-http-vertx} in
 * the build along with the REST configuration that carried the base path.
 *
 * <p>A mount names the pipeline that answers it, and the edge looks that pipeline up in the
 * registry by the same id (docs/camel-removal.md structural decision 1). It used to name a
 * {@code direct:} endpoint the edge resolved by reading the route model; both left with slice 2b.
 */
public final class HttpMounts {

    /** Registry name, so an installer can declare a mount and the edge can read them all. */
    public static final String BEAN = "tesseraqlHttpMounts";

    /**
     * One surface.
     *
     * @param method   the HTTP method, upper case
     * @param path     the URL, base-relative, with {@code {name}} parameter spelling
     * @param pipeline the id of the pipeline that answers here
     */
    public record Mount(String method, String path, String pipeline) {
    }

    private final List<Mount> mounts = new ArrayList<>();

    /**
     * The context's mount table, created on first use.
     *
     * <p>Synchronized because two installers can declare their first mount at once, and a
     * check-then-bind that raced would strand one of their tables. This is the only class-wide
     * lock left: the table's own methods lock the table (docs/vertx-native.md decision 4), so
     * one application's reload no longer serializes against another's.
     */
    public static synchronized HttpMounts of(RuntimeContext context) {
        HttpMounts mounts = context.lookup(BEAN, HttpMounts.class);
        if (mounts == null) {
            mounts = new HttpMounts();
            context.bind(BEAN, mounts);
        }
        return mounts;
    }

    /**
     * Declares where a route answers, replacing any earlier declaration for the same endpoint.
     *
     * <p>Replacing rather than appending is what makes a hot reload idempotent: a recompiled
     * route re-declares its mount, and a table that grew an entry per reload would mount the same
     * URL twice.
     */
    public synchronized void mount(String method, String path, String pipeline) {
        mounts.removeIf(mount -> mount.pipeline().equals(pipeline));
        mounts.add(new Mount(method.toUpperCase(Locale.ROOT), path, pipeline));
    }

    /** Every mount declared so far, in declaration order. */
    public synchronized List<Mount> all() {
        return List.copyOf(mounts);
    }

    /** Forgets the mounts of routes being replaced, so a hot reload does not accumulate them. */
    public synchronized void forget(String pipeline) {
        mounts.removeIf(mount -> mount.pipeline().equals(pipeline));
    }
}
