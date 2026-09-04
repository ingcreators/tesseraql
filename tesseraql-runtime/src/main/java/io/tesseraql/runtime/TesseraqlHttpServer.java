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
    private final HttpEdgeSettings settings;
    private Vertx vertx;
    private Vertx created;
    private HttpServer server;
    /** The port actually bound — the requested one, or the kernel's pick when 0 was asked. */
    private volatile int boundPort;

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
            VertxOptions options, HttpEdgeSettings settings) {
        this.runtimeContext = runtimeContext;
        this.host = host;
        this.port = port;
        this.shared = shared;
        this.options = options;
        this.settings = settings;
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
        // Created once, here, rather than by the body handler on the first form post: an
        // unwritable location then fails the boot with the path in the message, instead of
        // failing every form post at request time with the router's untyped 500.
        java.nio.file.Files.createDirectories(settings.uploadsDirectory());
        runtimeContext.bind(HttpEdgeBeans.BODY_HANDLER,
                HttpEdgeBeans.newBodyHandler(settings));
        server = vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(router);
        server.listen(port, host).toCompletionStage().toCompletableFuture()
                .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boundPort = server.actualPort();
        LOG.info("HTTP server listening on {}:{}", host, boundPort);
    }

    /**
     * The port actually bound. Asking for port 0 binds an ephemeral one — the answer to the
     * pick-a-free-port-then-bind race the integration suite used to run per boot, where another
     * fork could take the picked port in the window between the probe and the bind.
     */
    int actualPort() {
        return boundPort;
    }

    @Override
    public void stop() throws Exception {
        // The created Vertx closes even when the server's close times out or throws: the
        // context logs a failed stop and moves on, so a leak here was permanent — event loops
        // and acceptor threads alive for the rest of the process.
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture()
                        .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                server = null;
            }
        } finally {
            if (created != null) {
                created.close().toCompletionStage().toCompletableFuture()
                        .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                created = null;
            }
            vertx = null;
        }
    }
}
