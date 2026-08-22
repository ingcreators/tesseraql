package io.tesseraql.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime's HTTP server and router (docs/http-edge.md decision 1).
 *
 * <p>This was {@code camel-platform-http-vertx}'s job, and it stopped being anything else: the
 * REST DSL that created its consumers is gone, so the component was bootstrapping a Vert.x server
 * for surfaces that mount themselves. Owning it is a smaller thing than it sounds — a server, a
 * router, and knowing which of them this runtime created — and it is what lets the dependency
 * leave.
 *
 * <p><strong>A shared Vert.x is used and not closed.</strong> The host binds one instance for
 * every application it runs (docs/http-threading.md decision 4), so stopping one application must
 * leave it alone; an instance created here belongs here and is closed with the context. That is
 * the same rule the component followed, and the reason a canary activation does not take the other
 * members' event loops with it.
 */
final class TesseraqlHttpServer implements RuntimeContext.Service {

    private static final Logger LOG = LoggerFactory.getLogger(TesseraqlHttpServer.class);

    private static final long BIND_TIMEOUT_SECONDS = 30;

    private final RuntimeContext runtimeContext;
    private final String host;
    private final int port;
    private final Vertx shared;
    private final VertxOptions options;
    private Vertx vertx;
    private Vertx created;
    private HttpServer server;

    /**
     * The transport is passed, not looked up (docs/vertx-native.md decision 6). The code that
     * constructs this server is the code that already decided whether the host's shared Vert.x
     * carries it or the declared options build one — the by-type registry lookup this replaces
     * answered null when more than one instance matched, and a second bound instance made the
     * server silently build a third with default pools, the exact default docs/http-threading.md
     * decision 1 exists to prevent.
     *
     * @param shared  the host's Vert.x, ridden and never closed; null when standalone
     * @param options what to build one from when standalone; null falls back to defaults
     */
    TesseraqlHttpServer(RuntimeContext runtimeContext, String host, int port, Vertx shared,
            VertxOptions options) {
        this.runtimeContext = runtimeContext;
        this.host = host;
        this.port = port;
        this.shared = shared;
        this.options = options;
    }

    @Override
    public void start() throws Exception {
        vertx = shared;
        if (vertx == null) {
            created = options == null ? Vertx.vertx() : Vertx.vertx(options);
            vertx = created;
            LOG.debug("Created a Vert.x instance for this runtime");
        }
        // The published fact: which transport this runtime serves on. The multi-app suite reads
        // it to assert that every member of one host rides one instance
        // (docs/http-threading.md decision 4).
        runtimeContext.bind(io.tesseraql.pipeline.TesseraqlProperties.VERTX_BEAN, vertx);
        Router router = Router.router(vertx);
        runtimeContext.bind(HttpEdgeBeans.ROUTER, router);
        runtimeContext.bind(HttpEdgeBeans.BODY_HANDLER,
                HttpEdgeBeans.newBodyHandler());
        server = vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(router);
        server.listen(port, host).toCompletionStage().toCompletableFuture()
                .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        LOG.info("HTTP server listening on {}:{}", host, port);
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture()
                    .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server = null;
        }
        if (created != null) {
            created.close().toCompletionStage().toCompletableFuture()
                    .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            created = null;
        }
        vertx = null;
    }
}
