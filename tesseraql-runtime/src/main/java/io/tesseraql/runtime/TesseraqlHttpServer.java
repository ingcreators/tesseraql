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

    /** TQL-YAML-1113: the request-body spool directory exists but cannot be written to. */
    private static final io.tesseraql.core.error.TqlErrorCode UPLOADS_NOT_WRITABLE = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.YAML, 1113);

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
        prepareUploadsDirectory(settings.uploadsDirectory());
        runtimeContext.bind(HttpEdgeBeans.BODY_HANDLER,
                HttpEdgeBeans.newBodyHandler(settings));
        server = vertx.createHttpServer(serverOptions(settings))
                .requestHandler(router);
        server.listen(port, host).toCompletionStage().toCompletableFuture()
                .get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boundPort = server.actualPort();
        LOG.info("HTTP server listening on {}:{}", host, boundPort);
    }

    /**
     * The server options this runtime declares, rather than the ones it would inherit
     * (docs/http-edge-robustness.md decisions 4 and 5).
     *
     * <p>Two of Vert.x's three form-decoding defaults move. {@code maxFormAttributeSize} was
     * 8192 bytes, so a textarea of about 2,700 characters of Japanese was refused with a bare
     * {@code 400} whose body read {@code java.io.IOException: Size exceed allowed maximum
     * capacity}, while {@code tesseraql.http.maxBodyBytes} — the bound this framework publishes
     * for exactly those bytes — never got to speak. {@code maxFormFields} was 256.
     *
     * <p>The attribute ceiling sits one transport delivery <em>above</em> the body limit rather
     * than at it. Two bounds on the same bytes are a race the decoder wins: measured against
     * this jar, with both set to a 1 MB body limit an over-limit chunked body answered 400,
     * and with 64 KB of headroom the same body answered 413. An HTTP/1.1 chunk is capped at
     * 8192 bytes and an h2 frame at 16 KB, so the headroom guarantees the body handler's
     * counter is crossed on a strictly earlier chunk and the documented 413 wins by
     * construction.
     *
     * <p>{@code maxFormBufferedBytes} is deliberately left at Vert.x's 1024. It bounds the
     * <em>undecoded remainder</em>, not a field: measured, a 5,000,000-byte value posted in 610
     * flushed 8 KB chunks decodes to 200 with the correct length while that bound stays at its
     * default, because the decoder drains the remainder into the attribute on every parse. It
     * grows only for input nothing can parse at all — a field name with no {@code =} — which is
     * what it exists to refuse, and it is the decoder's only self-termination.
     */
    static HttpServerOptions serverOptions(HttpEdgeSettings settings) {
        HttpServerOptions options = new HttpServerOptions()
                .setMaxFormFields(settings.maxFormFields())
                .setMaxFormAttributeSize(settings.maxFormAttributeSize());
        if (settings.idleTimeoutSeconds() > 0) {
            options.setIdleTimeout(settings.idleTimeoutSeconds());
        }
        return options;
    }

    /**
     * Creates the request-body spool and proves it can be written to, before the server binds.
     *
     * <p>Created once here rather than by the body handler on the first form post, because
     * vertx-web creates its uploads directory for every url-encoded post and not only for
     * multipart: an unwritable location failed every form the runtime served, sign-in included,
     * with the router's untyped 500 and no line naming the directory.
     *
     * <p>Present is not the same as writable, which is why the probe exists rather than the
     * {@code createDirectories} call alone. On an existing directory that the process cannot
     * write, {@code createDirectories} returns normally on JDK 25 and the failure surfaces only
     * at request time; and where the leaf is missing under an unwritable parent, the exception
     * names neither the leaf nor the subsystem. One deployment produces exactly that state — a
     * work tree left root-owned by a single {@code --user root} run, or a host bind mount over
     * the volume — and it is the state this guarantee exists for.
     *
     * <p>The refusal is a boot failure on purpose. Under {@code tesseraql host} that closes every
     * member already started and aborts the stack, which is a heavier consequence than a failing
     * form and is stated in docs/http-edge-robustness.md decision 7: an edge that cannot spool a
     * request body cannot answer a sign-in, so a runtime serving with it broken is a runtime
     * pretending.
     */
    static void prepareUploadsDirectory(java.nio.file.Path uploads) {
        java.nio.file.Path probe = uploads.resolve(".tesseraql-write-probe");
        try {
            java.nio.file.Files.createDirectories(uploads);
            java.nio.file.Files.deleteIfExists(probe);
            java.nio.file.Files.createFile(probe);
            java.nio.file.Files.delete(probe);
        } catch (java.io.IOException unwritable) {
            throw new io.tesseraql.core.error.TqlException(UPLOADS_NOT_WRITABLE,
                    "The request-body spool directory " + uploads + " is not writable: "
                            + unwritable + ". Every form post spools through it, so the runtime"
                            + " would answer none. Set tesseraql.app.work to a writable location"
                            + " or grant the runtime user write access to this directory.");
        }
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
