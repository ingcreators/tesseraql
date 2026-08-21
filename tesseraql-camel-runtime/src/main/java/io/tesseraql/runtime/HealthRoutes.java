package io.tesseraql.runtime;

import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answers liveness and readiness on the platform router, without a worker
 * (docs/http-threading.md decision 3): the remaining half of the decision that put a bound in
 * front of the worker pool.
 *
 * <p>The gate already let health through, because health is the one surface whose whole purpose is
 * to be answerable when nothing else is. That was half an answer. Health was still a Camel route,
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
 * orchestrator's own timeout fire. So the memo is served while it is younger than
 * {@link #STALE_AFTER_TTLS} times the TTL, and beyond that readiness answers {@code DOWN}: the
 * runtime does not know that it is ready, and not knowing is not readiness.
 */
final class HealthRoutes {

    /**
     * After the admission gate, like every other surface that does not take a worker.
     *
     * <p>The gate exempts this prefix already — that is decision 3's first half — so this order
     * only keeps it ahead of the Camel routes.
     */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

    /**
     * How many TTLs an unrefreshed roll-up is still an answer.
     *
     * <p>One would flap: a refresh legitimately takes as long as the probe it performs, so the
     * memo is routinely a little older than its TTL while the next one is being computed. Three
     * means two consecutive refreshes failed to land, which is not a hiccup. An operator whose
     * probes are legitimately slower than this raises {@code tesseraql.diagnostics.readinessTtl},
     * the number that already governs how fresh readiness is.
     */
    private static final int STALE_AFTER_TTLS = 3;

    private static final String UP = "{\"status\":\"UP\"}";

    private final OpsDashboard dashboard;
    private final long ttlMillis;
    /** At most one refresh at a time: a burst of polls must not become a burst of probes. */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private HealthRoutes(OpsDashboard dashboard) {
        this.dashboard = dashboard;
        this.ttlMillis = Math.max(1, dashboard.healthTtl().toMillis());
    }

    /** Mounts liveness and readiness on the started platform router, under the app's base path. */
    static void install(RuntimeContext camelContext, int port, OpsDashboard dashboard) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(camelContext);
        HealthRoutes health = new HealthRoutes(dashboard);
        String mount = io.tesseraql.camel.BasePath
                .of(camelContext.beans()) + "/_tesseraql/health";
        // Liveness is a constant: it says the process is running, and it must never consult a
        // dependency, which is the whole distinction between it and readiness.
        router.route(HttpMethod.GET, mount + "/live").order(AFTER_THE_GATE)
                .handler(ctx -> respond(ctx, 200, UP));
        router.route(HttpMethod.GET, mount + "/ready").order(AFTER_THE_GATE)
                .handler(health::readiness);
        // The bare path serves the same roll-up, as it always has.
        router.route(HttpMethod.GET, mount).order(AFTER_THE_GATE).handler(health::readiness);
    }

    private void readiness(RoutingContext ctx) {
        Optional<OpsDashboard.HeldHealth> held = dashboard.heldHealth();
        if (held.isEmpty()) {
            // Nothing has been computed yet, which happens once per process. This one request
            // waits for the first roll-up — on a thread of its own, never on the event loop.
            firstRollUp(ctx);
            return;
        }
        long age = held.get().ageMillis();
        if (age >= ttlMillis) {
            refresh();
        }
        String status = age >= ttlMillis * STALE_AFTER_TTLS ? "DOWN" : held.get().report().status();
        answer(ctx, status);
    }

    private void firstRollUp(RoutingContext ctx) {
        Context connection = ctx.vertx().getOrCreateContext();
        Thread.ofVirtual().name("tql-readiness-first").start(() -> {
            String status = rollUp();
            connection.runOnContext(reply -> answer(ctx, status));
        });
    }

    /**
     * Recomputes behind the answer already given.
     *
     * <p>The poll that finds the memo due does not wait for the new one: it is answered from what
     * is held, and the next poll gets the fresher result. That is what makes readiness cost the
     * event loop and nothing else, and the staleness rule above is what keeps it honest when the
     * refresh never returns.
     */
    private void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("tql-readiness-refresh").start(() -> {
            try {
                rollUp();
            } finally {
                refreshing.set(false);
            }
        });
    }

    /** The roll-up, or {@code DOWN} when computing it threw — never a 500 out of a probe. */
    private String rollUp() {
        try {
            return dashboard.health().status();
        } catch (RuntimeException unavailable) {
            return "DOWN";
        }
    }

    private static void answer(RoutingContext ctx, String status) {
        // Three constants, so the JSON is written rather than serialized; the shape is the one
        // the Camel route produced, byte for byte.
        respond(ctx, "DOWN".equals(status) ? 503 : 200, "{\"status\":\"" + status + "\"}");
    }

    private static void respond(RoutingContext ctx, int code, String body) {
        if (ctx.response().ended()) {
            return;
        }
        ctx.response().setStatusCode(code)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .end(body);
    }
}
