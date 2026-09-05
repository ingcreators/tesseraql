package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.pipeline.RuntimeContext;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime-wide bound on requests in flight (docs/http-threading.md decision 3): a permit taken
 * on the event loop before the request reaches a route, released when the response ends.
 *
 * <p>There was none. Route processing runs on a fixed worker pool, and requests arriving while
 * every worker is blocked in JDBC queued in Vert.x's blocked-task queue, which has no bound.
 * Everything the runtime served queued with them — including health and readiness, so a saturated
 * runtime could not answer the question "are you saturated" and an orchestrator killed it for the
 * silence. Refusing beyond a declared number turns that back into what it is: a slowdown.
 *
 * <p>Health is checked <em>before</em> the permit for exactly that reason. It is the one surface
 * whose whole purpose is to be answerable when nothing else is.
 */
final class HttpAdmission {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAdmission.class);

    /** TQL-RATE-4293: the runtime is at its in-flight bound; the route limiter's 4291 is per route. */
    private static final TqlErrorCode AT_CAPACITY = new TqlErrorCode(TqlDomain.RATE, 4293);

    /**
     * Ahead of every route the runtime registers. Vert.x orders routes by registration index unless told
     * otherwise, and this handler installs after {@code context.start()} — the router does not
     * exist before it — so without an explicit order it would sit behind the handlers it exists to
     * guard.
     */
    private static final int BEFORE_EVERY_ROUTE = Integer.MIN_VALUE;

    private final Semaphore permits;
    private final String healthPrefix;
    /**
     * The asset mount, which this gate lets through (docs/http-threading.md decision 6).
     *
     * <p>The bound exists to protect the worker pool, and since assets moved onto the router they
     * do not take a worker. Refusing a stylesheet because the database is slow would put back the
     * coupling that move exists to remove.
     */
    private final String assetPrefix;
    private final AtomicLong refused = new AtomicLong();

    private HttpAdmission(int maxInFlight, String healthPrefix, String assetPrefix) {
        this.permits = new Semaphore(maxInFlight);
        this.healthPrefix = healthPrefix;
        this.assetPrefix = assetPrefix;
    }

    /**
     * Installs the gate on the started platform router.
     *
     * <p>Called from the same post-start hook the SSE endpoints use: the router is created when
     * the HTTP server service starts, so there is nothing to register on before that.
     *
     * <p>Registered after {@link UnicodePaths}, which shares this order value and is separated
     * from it by registration index alone. That ordering is load-bearing: a Unicode path is
     * rerouted in its decoded form, routing restarts from the first handler, and this gate then
     * reads the decoded path once. Registering ahead of it would charge a permit against the
     * percent-encoded spelling and again against the decoded one.
     */
    static void install(RuntimeContext runtimeContext, int maxInFlight) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(runtimeContext);
        HttpAdmission gate = new HttpAdmission(maxInFlight,
                io.tesseraql.pipeline.BasePath.of(runtimeContext.beans())
                        + "/_tesseraql/health",
                AssetRoutes.mountOf(runtimeContext));
        router.route().order(BEFORE_EVERY_ROUTE).handler(gate::admit);
        LOG.debug("HTTP admission installed: {} requests in flight", maxInFlight);
    }

    private void admit(io.vertx.ext.web.RoutingContext ctx) {
        String path = matchedPath(ctx);
        if (path != null && (path.startsWith(healthPrefix) || path.startsWith(assetPrefix))) {
            ctx.next();
            return;
        }
        if (!permits.tryAcquire()) {
            refuse(ctx);
            return;
        }
        // Fires on completion and on failure alike; a permit released only on the happy path is a
        // permit the runtime loses every time something goes wrong, which is when it has least to
        // spare.
        ctx.addEndHandler(ended -> permits.release());
        ctx.next();
    }

    /**
     * The path the router will match this request on, which is not the path the client sent.
     *
     * <p>vertx-web matches routes on {@code RoutingContext.normalizedPath()} — dot segments
     * removed, unreserved escapes decoded — and this gate tested {@code request().path()}, the
     * target as transmitted. The two readings disagreed in both directions. A request could
     * satisfy a carve-out and then be routed somewhere else entirely:
     * {@code GET /_tesseraql/health/../../api/orders} took no permit and reached
     * {@code /api/orders}, so the bound this gate exists to hold was one dot segment away from
     * not existing. And a spelling the router accepts for an exempt mount, such as
     * {@code /_tesseraql/%68ealth}, was charged a permit it should never have paid.
     *
     * <p>Returns null when the path cannot be normalized — vertx-web raises
     * {@code IllegalArgumentException} on an invalid escape such as {@code /%zz}. That request
     * matches no route either, since every route's own match normalizes the same way, so it is
     * answered 400 without reaching an application. It is charged a permit rather than exempted:
     * a request this gate cannot classify is not a request it should wave through, and the permit
     * returns with the 400.
     */
    private static String matchedPath(io.vertx.ext.web.RoutingContext ctx) {
        try {
            return ctx.normalizedPath();
        } catch (IllegalArgumentException unnormalizable) {
            return null;
        }
    }

    private void refuse(io.vertx.ext.web.RoutingContext ctx) {
        long total = refused.incrementAndGet();
        if (total == 1 || total % 100 == 0) {
            LOG.warn("HTTP admission refused {} request(s): the runtime is at"
                    + " tesseraql.http.maxInFlight", total);
        }
        ctx.response()
                .setStatusCode(503)
                // Retryable, and the client should be told rather than left to guess: an
                // unexplained 503 reads as broken where a bounded one reads as busy.
                .putHeader("Retry-After", "1")
                .putHeader("Content-Type", "application/json; charset=utf-8")
                // The code, not a message built from runtime state — the same envelope discipline
                // the SSE refusal path follows.
                .end(io.tesseraql.core.error.ErrorEnvelope.json(AT_CAPACITY,
                        "The runtime is at capacity"));
    }
}
