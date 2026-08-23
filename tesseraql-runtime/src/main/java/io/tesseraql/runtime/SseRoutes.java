package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-Sent Events endpoints on the platform's Vert.x router (docs/copilot.md "The SSE
 * transport", docs/inbox.md "Live badge"). These are raw router routes, not compiled
 * pipelines, for one load-bearing reason: a pipeline answers with a complete body, and the
 * InputStream pump behind it only flushes full buffers — an SSE frame must reach the wire the
 * moment it is written. Registration happens after the context (and with it the
 * platform HTTP server) has started.
 *
 * <p>Per connection: the browser session authenticates exactly like {@code auth: browser}
 * routes ({@link BrowserAuthenticator}); the handler's {@code begin} validates and may
 * refuse with a {@link TqlException} — rendered as the framework's JSON error envelope
 * with its mapped status — BEFORE the stream opens. The producer then runs on a virtual
 * thread; every frame write hops to the connection's event-loop context, and a client
 * disconnect fails the next write, which ends the producer.
 */
public final class SseRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(SseRoutes.class);

    private SseRoutes() {
    }

    /** One connected SSE client; {@code data} must be single-line by construction. */
    public interface Writer {
        void event(String name, String data) throws IOException;

        /** Sets the browser's reconnect delay — long-lived streams send it at open. */
        void retry(long millis) throws IOException;
    }

    /** The producing side of one stream, run on its own virtual thread. */
    public interface Producer {
        void produce(Writer writer) throws Exception;
    }

    /** One SSE endpoint: {@code begin} gates the stream, the returned producer feeds it. */
    public interface Handler {
        Producer begin(Principal principal, Function<String, String> query);
    }

    /** The app's configured response headers, empty when the app declares none. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> securityHeaders(RuntimeContext runtimeContext) {
        java.util.Map<String, String> headers = runtimeContext.lookup(
                TesseraqlProperties.RESPONSE_HEADERS_BEAN, java.util.Map.class);
        return headers == null ? java.util.Map.of() : headers;
    }

    /**
     * Registers {@code GET path} as an SSE endpoint on the started platform router. The path is
     * base-relative and mounted under the application's prefix (docs/base-path.md): these are
     * router routes, so the mount table — where the prefix reaches every other framework
     * endpoint at once — does not carry them.
     */
    public static void register(RuntimeContext runtimeContext, String path,
            Handler handler) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(runtimeContext);
        String mounted = io.tesseraql.pipeline.BasePath
                .of(runtimeContext.beans()) + path;
        router.route(HttpMethod.GET, mounted)
                .handler(ctx -> serve(runtimeContext, ctx, mounted, handler));
    }

    private static void serve(RuntimeContext runtimeContext,
            RoutingContext ctx, String path, Handler handler) {
        HttpServerResponse response = ctx.response();
        // The connection's event-loop context — captured here, on it — is where every
        // response mutation is dispatched; the queue preserves write order.
        Context connection = ctx.vertx().getOrCreateContext();
        AtomicBoolean gone = new AtomicBoolean();
        response.closeHandler(closed -> gone.set(true));
        response.exceptionHandler(failure -> gone.set(true));
        Thread.ofVirtual().name("tql-sse-" + path).start(() -> {
            try {
                SessionStore sessions = runtimeContext.lookup(
                        TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
                String cookie = ctx.request().getHeader("Cookie");
                Principal principal = new BrowserAuthenticator(sessions).authenticate(cookie);
                // Kept so every frame can re-check it: authenticating once at connect made
                // "sign out others" and a password change take up to the stream's fifteen-minute
                // lifetime to bite, against what security-hardening.md promises.
                String sessionId = sessions == null || cookie == null
                        ? null
                        : sessions.sessionIdFromCookie(cookie);
                Producer producer = handler.begin(principal, ctx.request()::getParam);
                connection.runOnContext(open -> {
                    if (!gone.get()) {
                        response.setStatusCode(200);
                        response.putHeader("Content-Type", "text/event-stream; charset=utf-8");
                        response.putHeader("Cache-Control", "no-store");
                        // Buffering reverse proxies (nginx) must pass frames through live.
                        response.putHeader("X-Accel-Buffering", "no");
                        // The app's security.responseHeaders, before the first frame: a stream
                        // cannot be given headers by a completion hook, which is why the
                        // response-wide mechanism the design leaned toward could not reach here.
                        securityHeaders(runtimeContext).forEach(response::putHeader);
                        response.setChunked(true);
                    }
                });
                producer.produce(frameWriter(connection, response, gone, sessions, sessionId));
                connection.runOnContext(end -> {
                    if (!gone.get() && !response.ended()) {
                        response.end();
                    }
                });
            } catch (TqlException refusal) {
                // begin() refused before the stream opened: the framework's error envelope.
                connection.runOnContext(refuse -> {
                    if (!gone.get() && !response.ended()) {
                        int status = ErrorResponseRenderer.httpStatus(refusal.code());
                        response.setStatusCode(status);
                        if (status == 429 || status == 503) {
                            // A capacity refusal is retryable; EventSource clients reconnect on
                            // their own schedule, API clients get the standard hint.
                            response.putHeader("Retry-After", "5");
                        }
                        response.putHeader("Content-Type", "application/json; charset=utf-8");
                        // The code, not the exception text. Every other endpoint answers with a
                        // generic phrase; this one concatenated the internal message into JSON —
                        // the same leak the Studio reload stub had, and with escaping that
                        // covered quotes and backslashes but not control characters. The detail
                        // belongs in the log.
                        LOG.debug("SSE stream {} refused: {}", path, refusal.getMessage());
                        response.end(io.tesseraql.core.error.ErrorEnvelope
                                .json(refusal.code(), "The request was refused"));
                    }
                });
            } catch (IOException ended) {
                // Either the client went away — the normal end of a stream — or the session was
                // invalidated under it. Both end here, and both must actually close the response:
                // leaving it open held the client on a stream that would never produce again.
                LOG.debug("SSE stream {} ended early: {}", path, ended.getMessage());
                connection.runOnContext(close -> {
                    if (!response.ended()) {
                        response.end();
                    }
                });
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
            AtomicBoolean gone, SessionStore sessions, String sessionId) {
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
                // Re-checked per frame rather than at connect: an invalidated session must stop
                // receiving data now, not when the stream happens to expire. An IOException is
                // how this loop already says "stop", so the stream closes the same clean way a
                // departed client does.
                if (sessionId != null && sessions != null && sessions.session(sessionId) == null) {
                    gone.set(true);
                    throw new IOException("The session ended");
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
