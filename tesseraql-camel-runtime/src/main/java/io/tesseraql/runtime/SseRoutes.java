package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.BrowserAuthenticator;
import io.tesseraql.security.session.SessionStore;
import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.camel.CamelContext;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-Sent Events endpoints on the platform's Vert.x router (docs/copilot.md "The SSE
 * transport", docs/inbox.md "Live badge"). These are raw router routes, not Camel routes,
 * for one load-bearing reason: a Camel exchange answers with a complete body, and the
 * platform-http InputStream pump only flushes full buffers — an SSE frame must reach the
 * wire the moment it is written. Registration happens after the context (and with it the
 * platform HTTP server) has started.
 *
 * <p>Per connection: the browser session authenticates exactly like {@code auth: browser}
 * routes ({@link BrowserAuthenticator}); the handler's {@code begin} validates and may
 * refuse with a {@link TqlException} — rendered as the framework's JSON error envelope
 * with its mapped status — BEFORE the stream opens. The producer then runs on a virtual
 * thread; every frame write hops to the connection's event-loop context, and a client
 * disconnect fails the next write, which ends the producer.
 */
final class SseRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(SseRoutes.class);

    private SseRoutes() {
    }

    /** One connected SSE client; {@code data} must be single-line by construction. */
    interface Writer {
        void event(String name, String data) throws IOException;

        /** Sets the browser's reconnect delay — long-lived streams send it at open. */
        void retry(long millis) throws IOException;
    }

    /** The producing side of one stream, run on its own virtual thread. */
    interface Producer {
        void produce(Writer writer) throws Exception;
    }

    /** One SSE endpoint: {@code begin} gates the stream, the returned producer feeds it. */
    interface Handler {
        Producer begin(Principal principal, Function<String, String> query);
    }

    /** Registers {@code GET path} as an SSE endpoint on the started platform router. */
    static void register(CamelContext camelContext, int port, String path, Handler handler) {
        VertxPlatformHttpRouter router = VertxPlatformHttpRouter.lookup(camelContext,
                VertxPlatformHttpRouter.getRouterNameFromPort(port));
        router.route(HttpMethod.GET, path)
                .handler(ctx -> serve(camelContext, router, ctx, path, handler));
    }

    private static void serve(CamelContext camelContext, VertxPlatformHttpRouter router,
            RoutingContext ctx, String path, Handler handler) {
        HttpServerResponse response = ctx.response();
        // The connection's event-loop context — captured here, on it — is where every
        // response mutation is dispatched; the queue preserves write order.
        Context connection = router.vertx().getOrCreateContext();
        AtomicBoolean gone = new AtomicBoolean();
        response.closeHandler(closed -> gone.set(true));
        response.exceptionHandler(failure -> gone.set(true));
        Thread.ofVirtual().name("tql-sse-" + path).start(() -> {
            try {
                SessionStore sessions = camelContext.getRegistry().lookupByNameAndType(
                        TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
                Principal principal = new BrowserAuthenticator(sessions)
                        .authenticate(ctx.request().getHeader("Cookie"));
                Producer producer = handler.begin(principal, ctx.request()::getParam);
                connection.runOnContext(open -> {
                    if (!gone.get()) {
                        response.setStatusCode(200);
                        response.putHeader("Content-Type", "text/event-stream; charset=utf-8");
                        response.putHeader("Cache-Control", "no-store");
                        // Buffering reverse proxies (nginx) must pass frames through live.
                        response.putHeader("X-Accel-Buffering", "no");
                        response.setChunked(true);
                    }
                });
                producer.produce(frameWriter(connection, response, gone));
                connection.runOnContext(end -> {
                    if (!gone.get() && !response.ended()) {
                        response.end();
                    }
                });
            } catch (TqlException refusal) {
                // begin() refused before the stream opened: the framework's error envelope.
                connection.runOnContext(refuse -> {
                    if (!gone.get() && !response.ended()) {
                        response.setStatusCode(ErrorResponseRenderer.httpStatus(refusal.code()));
                        response.putHeader("Content-Type", "application/json; charset=utf-8");
                        response.end("{\"error\":{\"code\":\"" + refusal.code() + "\","
                                + "\"message\":\"" + refusal.getMessage()
                                        .replace("\\", "\\\\").replace("\"", "'")
                                + "\"}}");
                    }
                });
            } catch (IOException clientGone) {
                // The client went away mid-stream — normal end of a stream.
                LOG.debug("SSE stream {} ended early: {}", path, clientGone.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception unexpected) {
                LOG.warn("SSE stream {} failed", path, unexpected);
                connection.runOnContext(close -> {
                    if (!response.ended()) {
                        // Mid-stream failure: drop the connection so the client reconnects.
                        ctx.request().connection().close();
                    }
                });
            }
        });
    }

    private static Writer frameWriter(Context connection, HttpServerResponse response,
            AtomicBoolean gone) {
        return new Writer() {
            @Override
            public void event(String name, String data) throws IOException {
                // An SSE data payload is one line per frame; producers encode newlines as
                // markup before framing, this guard only keeps the wire valid.
                String line = data == null ? "" : data.replace("\r", "").replace("\n", "");
                write("event: " + name + "\ndata: " + line + "\n\n");
            }

            @Override
            public void retry(long millis) throws IOException {
                write("retry: " + millis + "\n\n");
            }

            private void write(String frame) throws IOException {
                if (gone.get()) {
                    throw new IOException("The client closed the stream");
                }
                connection.runOnContext(deliver -> {
                    if (!gone.get() && !response.ended()) {
                        response.write(io.vertx.core.buffer.Buffer.buffer(
                                frame.getBytes(StandardCharsets.UTF_8)))
                                .onFailure(failure -> gone.set(true));
                    }
                });
            }
        };
    }
}
