package io.tesseraql.camel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.camel.CamelContext;

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
 * <p>A mount names the {@code direct:} endpoint rather than the route id, because that is what the
 * call site already had in its hand: the id lives on the {@code from(...)} on the next line. The
 * edge resolves one to the other by reading the route model, which is the same place it reads the
 * pipeline from.
 */
public final class HttpMounts {

    /** Registry name, so a route builder can declare a mount and the edge can read them all. */
    public static final String BEAN = "tesseraqlHttpMounts";

    /**
     * One surface.
     *
     * @param method  the HTTP method, upper case
     * @param path    the URL, base-relative, with Camel's {@code {name}} parameter spelling
     * @param direct  the {@code direct:} endpoint the route consumes from
     */
    public record Mount(String method, String path, String direct) {
    }

    private final List<Mount> mounts = new ArrayList<>();

    /**
     * Declares where a route answers, replacing any earlier declaration for the same endpoint.
     *
     * <p>Replacing rather than appending is what makes a hot reload idempotent: a recompiled
     * route re-declares its mount, and a table that grew an entry per reload would mount the same
     * URL twice.
     */
    public static synchronized void mount(CamelContext context, String method, String path,
            String direct) {
        HttpMounts held = of(context);
        held.mounts.removeIf(mount -> mount.direct().equals(direct));
        held.mounts.add(new Mount(method.toUpperCase(Locale.ROOT), path, direct));
    }

    /** Every mount declared so far, in declaration order. */
    public static synchronized List<Mount> all(CamelContext context) {
        return List.copyOf(of(context).mounts);
    }

    /** Forgets the mounts of routes being replaced, so a hot reload does not accumulate them. */
    public static synchronized void forget(CamelContext context, String direct) {
        of(context).mounts.removeIf(mount -> mount.direct().equals(direct));
    }

    private static HttpMounts of(CamelContext context) {
        HttpMounts mounts = context.getRegistry().lookupByNameAndType(BEAN, HttpMounts.class);
        if (mounts == null) {
            mounts = new HttpMounts();
            context.getRegistry().bind(BEAN, mounts);
        }
        return mounts;
    }
}
