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
 * on the event loop before the request reaches Camel, released when the response ends.
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
     * Ahead of every route Camel registers. Vert.x orders routes by registration index unless told
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
     */
    static void install(RuntimeContext camelContext, int port, int maxInFlight) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(camelContext);
        HttpAdmission gate = new HttpAdmission(maxInFlight,
                io.tesseraql.camel.BasePath.of(camelContext.beans())
                        + "/_tesseraql/health",
                AssetRoutes.mountOf(camelContext));
        router.route().order(BEFORE_EVERY_ROUTE).handler(gate::admit);
        LOG.debug("HTTP admission installed: {} requests in flight", maxInFlight);
    }

    private void admit(io.vertx.ext.web.RoutingContext ctx) {
        String path = ctx.request().path();
        if (path.startsWith(healthPrefix) || path.startsWith(assetPrefix)) {
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
                .end("{\"error\":{\"code\":\"" + AT_CAPACITY
                        + "\",\"message\":\"The runtime is at capacity\"}}");
    }
}
