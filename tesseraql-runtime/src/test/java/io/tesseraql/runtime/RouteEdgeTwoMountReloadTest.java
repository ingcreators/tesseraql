package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One pipeline, two methods, and a hot reload (docs/list-surface.md decision 10).
 *
 * <p>A snapshot-paginated page answers both GET and POST at one URL, and the compiler mounts both
 * onto a single pipeline id — the pager re-posts the membership tokens to the same place. The edge
 * used to key its router bookkeeping by that pipeline id alone, so the two mounts reconciled
 * against <em>each other</em>: each one found the other's mount recorded under the shared key,
 * decided the route had moved, and remounted — and remount begins by taking the sibling's router
 * route off. The page came back POST-only, which a browser sees as a 404 on the list itself.
 *
 * <p>The controls matter as much as the failing case: a one-method route must still reload
 * cleanly, and a route that really is gone must still lose its mount. A fix that simply stopped
 * unmounting would pass the headline assertion and break both.
 */
class RouteEdgeTwoMountReloadTest {

    private Vertx vertx;
    private Router router;
    private RuntimeContext context;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
        context = new RuntimeContext();
        context.bind(HttpEdgeBeans.ROUTER, router);
        context.bind(HttpEdgeBeans.BODY_HANDLER, BodyHandler.create());
    }

    @AfterEach
    void tearDown() {
        context.close();
        vertx.close();
    }

    /** Registers a pipeline the edge will accept as compiled. */
    private void pipeline(String id) {
        Pipelines.of(context).compiling(List.of()).pipeline(id)
                .process(exchange -> exchange.response().status(200));
    }

    private Set<String> served() {
        return router.getRoutes().stream()
                .filter(route -> route.getPath() != null)
                .flatMap(route -> route.methods().stream()
                        .map(method -> method + " " + route.getPath()))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * The headline. An author adds {@code strategy: snapshot} to a page that is already serving,
     * and the reload mounts its POST — which, keyed by pipeline alone, took the live GET off the
     * router on its way past. The page the browser was reading 404s.
     *
     * <p>Deliberately the GAINS trigger rather than a two-mount boot: at boot the second mount
     * overwrites the first in the bookkeeping but leaves its Route live on the router, and that
     * orphan answers GET for the rest of the process — so a boot-then-reload assertion is GREEN
     * against the very defect it is written for. Measured, not reasoned about.
     */
    @Test
    @Timeout(60)
    void aPageThatGainsSnapshotPagingKeepsTheGetItWasAlreadyServing() {
        pipeline("queue.list");
        HttpMounts.of(context).mount("GET", "/queue", "queue.list");
        RouteEdge edge = RouteEdge.install(context);
        assertThat(served()).containsExactly("GET /queue");

        HttpMounts.of(context).mount("POST", "/queue", "queue.list");
        edge.refreshAll();

        assertThat(served())
                .as("adding the pager's POST must not unmount the GET the page is served on")
                .containsExactlyInAnyOrder("GET /queue", "POST /queue");
    }

    @Test
    @Timeout(60)
    void aSnapshotPageKeepsBothMethodsAcrossAReload() {
        pipeline("queue.list");
        HttpMounts.of(context).mount("GET", "/queue", "queue.list");
        HttpMounts.of(context).mount("POST", "/queue", "queue.list");

        RouteEdge edge = RouteEdge.install(context);
        assertThat(served())
                .as("both methods are on the router at boot")
                .containsExactlyInAnyOrder("GET /queue", "POST /queue");

        edge.refreshAll();

        assertThat(served())
                .as("a reload must not drop the GET half of a snapshot page — the pager posts to"
                        + " the same URL the browser gets")
                .containsExactlyInAnyOrder("GET /queue", "POST /queue");
    }

    @Test
    @Timeout(60)
    void aSnapshotPageThatMovesIsServedOnlyAtItsNewUrl() {
        pipeline("queue.list");
        HttpMounts.of(context).mount("GET", "/queue", "queue.list");
        HttpMounts.of(context).mount("POST", "/queue", "queue.list");
        RouteEdge edge = RouteEdge.install(context);

        HttpMounts.of(context).mount("GET", "/inbox", "queue.list");
        HttpMounts.of(context).mount("POST", "/inbox", "queue.list");
        edge.refreshAll();

        assertThat(served())
                .as("both methods move together, and nothing is left behind at the old URL")
                .containsExactlyInAnyOrder("GET /inbox", "POST /inbox");
    }

    @Test
    @Timeout(60)
    void aSingleMethodRouteStillReloadsAndStillGoesAway() {
        pipeline("orders.show");
        HttpMounts.of(context).mount("GET", "/orders", "orders.show");
        RouteEdge edge = RouteEdge.install(context);

        edge.refreshAll();
        assertThat(served()).containsExactly("GET /orders");

        HttpMounts.of(context).forget("orders.show");
        edge.refreshAll();
        assertThat(served())
                .as("a route that is no longer declared answers 404, not its last body")
                .isEmpty();
    }

    @Test
    @Timeout(60)
    void oneMethodOfATwoMethodRouteCanBeWithdrawnAlone() {
        pipeline("queue.list");
        HttpMounts.of(context).mount("GET", "/queue", "queue.list");
        HttpMounts.of(context).mount("POST", "/queue", "queue.list");
        RouteEdge edge = RouteEdge.install(context);

        // The page drops `strategy: snapshot`: the GET stays, the pager's POST goes.
        HttpMounts.of(context).forget("queue.list");
        HttpMounts.of(context).mount("GET", "/queue", "queue.list");
        edge.refreshAll();

        assertThat(served()).containsExactly("GET /queue");
        assertThat(router.getRoutes().stream()
                .anyMatch(route -> route.methods().contains(HttpMethod.POST)))
                .as("the withdrawn method is off the router, not merely untracked")
                .isFalse();
    }

    /** A stale Route object left on the router is invisible to {@link #served()} otherwise. */
    @Test
    @Timeout(60)
    void aReloadLeavesNoOrphanRoutesOnTheRouter() {
        pipeline("queue.list");
        HttpMounts.of(context).mount("GET", "/queue", "queue.list");
        HttpMounts.of(context).mount("POST", "/queue", "queue.list");
        RouteEdge edge = RouteEdge.install(context);

        List<Route> afterBoot = List.copyOf(router.getRoutes());
        edge.refreshAll();
        edge.refreshAll();

        assertThat(router.getRoutes())
                .as("repeated reloads must not accumulate router entries")
                .hasSameSizeAs(afterBoot);
    }
}
