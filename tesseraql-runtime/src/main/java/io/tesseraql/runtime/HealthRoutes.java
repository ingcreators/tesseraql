package io.tesseraql.runtime;

import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * Answers liveness and readiness on the platform router, without a worker
 * (docs/http-threading.md decision 3): the remaining half of the decision that put a bound in
 * front of the worker pool.
 *
 * <p>The gate already let health through, because health is the one surface whose whole purpose is
 * to be answerable when nothing else is. That was half an answer. Health was still a route,
 * so it still needed a worker, and a runtime with every worker inside a slow query still could not
 * say so — it just waited behind a bounded queue instead of an unbounded one. An orchestrator
 * that gets no answer to "are you saturated" concludes the process is dead and restarts it,
 * which is how a slowdown becomes an outage.
 *
 * <p><strong>Nothing about the answer changes; only which thread produces it.</strong> The same
 * roll-up, the same status word, the same 503 when it is {@code DOWN}, the same JSON. What
 * changes is that it comes off the memo {@code OpsDashboard} already keeps rather than out of a
 * route, so a poll costs a map read on the event loop.
 *
 * <p><strong>A roll-up that cannot be refreshed is not a readiness answer.</strong> Serving the
 * memo means serving something computed in the past, and the failure this has to survive is the
 * one where refreshing it hangs — a database that accepts connections and never answers holds the
 * probe for {@code connectionTimeout}, thirty seconds by default. Answering {@code UP} confidently
 * for thirty seconds would be worse than the route this replaces, which at least hung and let the
 * orchestrator's own timeout fire. The rule that decides when the memo stops being an answer —
 * and the correction that made it count from the refresh attempt rather than from a prober's
 * own silence — lives in {@link ReadinessMemo}, which this shares with the origin's roll-up.
 */
final class HealthRoutes {

    /**
     * After the admission gate, like every other surface that does not take a worker.
     *
     * <p>The gate exempts this prefix already — that is decision 3's first half — so this order
     * only keeps it ahead of the compiled routes.
     */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

    private static final String UP = "{\"status\":\"UP\"}";

    /** The runtime's one readiness memo, shared with the origin's roll-up over every member. */
    private final ReadinessMemo memo;

    private HealthRoutes(ReadinessMemo memo) {
        this.memo = memo;
    }

    /** Mounts liveness and readiness on the started platform router, under the app's base path. */
    static void install(RuntimeContext runtimeContext, ReadinessMemo memo) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(runtimeContext);
        HealthRoutes health = new HealthRoutes(memo);
        String mount = io.tesseraql.pipeline.BasePath
                .of(runtimeContext.beans()) + "/_tesseraql/health";
        // Liveness is a constant: it says the process is running, and it must never consult a
        // dependency, which is the whole distinction between it and readiness.
        // HEAD too (docs/edge-hygiene.md E4): a probe that heads a health endpoint is told the
        // status, and the transport withholds the body.
        router.route(HttpMethod.GET, mount + "/live").method(HttpMethod.HEAD).order(AFTER_THE_GATE)
                .handler(ctx -> respond(ctx, 200, UP));
        router.route(HttpMethod.GET, mount + "/ready").method(HttpMethod.HEAD)
                .order(AFTER_THE_GATE).handler(health::readiness);
        // The bare path serves the same roll-up, as it always has.
        router.route(HttpMethod.GET, mount).method(HttpMethod.HEAD).order(AFTER_THE_GATE)
                .handler(health::readiness);
    }

    private void readiness(RoutingContext ctx) {
        Optional<OpsDashboard.HeldHealth> held = memo.held();
        if (held.isEmpty()) {
            // Nothing has been computed yet, which happens once per process. This one request
            // waits for the first roll-up — on a thread of its own, never on the event loop.
            firstRollUp(ctx);
            return;
        }
        answer(ctx, memo.status(held.get()));
    }

    private void firstRollUp(RoutingContext ctx) {
        Context connection = ctx.vertx().getOrCreateContext();
        Thread.ofVirtual().name("tql-readiness-first").start(() -> {
            String status = memo.rollUp();
            try {
                connection.runOnContext(reply -> answer(ctx, status));
            } catch (java.util.concurrent.RejectedExecutionException closed) {
                // The roll-up outlived the runtime: it consults dependencies, so a close can
                // land while it is still running. There is no response left to answer and this
                // thread has no catch above it, so an escape here would print from a dead
                // process the same way the SSE producer's did.
            }
        });
    }

    private static void answer(RoutingContext ctx, String status) {
        // Three constants, so the JSON is written rather than serialized; the shape is the one
        // the route it replaced produced, byte for byte.
        respond(ctx, "DOWN".equals(status) ? 503 : 200, "{\"status\":\"" + status + "\"}");
    }

    private static void respond(RoutingContext ctx, int code, String body) {
        if (ctx.response().ended()) {
            return;
        }
        ctx.response().setStatusCode(code)
                .putHeader("Content-Type", "application/json; charset=utf-8");
        if (HeadRequests.isHead(ctx.request())) {
            HeadRequests.endWithoutBody(ctx.response(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            return;
        }
        ctx.response().end(body);
    }
}
