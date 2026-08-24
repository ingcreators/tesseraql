package io.tesseraql.runtime;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.streams.ReadStream;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frees the caller's upload when the origin answers before it ends — the relay's half of the
 * stream discipline {@link HttpBodyLimit} keeps inside a member.
 *
 * <p>An application refuses an over-limit or unauthorized request <em>early</em>, while the
 * caller is still writing the body: the member's 413 (or a session redirect's 302) completes
 * before the forwarded request does. The proxy library pipes the front request into the origin
 * request and relays the early answer back — and on the days it loses the race, it does not
 * finish the pipe: the origin request's writes stop progressing once its response has fully
 * arrived, the pipe parks the front request on a write-queue-full it will never be told has
 * drained, and the caller wedges. An HTTP/1.1 client cannot see a response while it is still
 * blocked writing the request, so the whole failure is silence until the caller's own timeout —
 * measured three ways on CI before the counters shipped in the drain watchdog named the frozen
 * hop (run 32686046591: the member's 413 write completed, the member's drain and the transport's
 * own byte count both froze at the same number, and every event loop sat idle — an event that
 * never fires, not a thread that blocks).
 *
 * <p>So the relay stops racing. A final response is the origin's word that the exchange is over
 * (RFC 9110 allows answering before reading the whole request, and a TesseraQL runtime never
 * answers early while still wanting the body), so the moment one arrives with the front request
 * still streaming, the rest of the upload is drained <em>here</em>: discarded at the front door
 * so the caller's write unblocks deterministically and the relayed answer becomes readable, with
 * the same zero-progress watchdog the member-side drain carries, and the origin request is reset
 * once the answer is relayed so the member's own drain sees a closed stream rather than one that
 * went quiet.
 *
 * <p>The upload cannot be handed off mid-stream by re-registering handlers: the proxy's pipe
 * nulls its source's handlers whenever a late write failure completes it, which would silently
 * detach the drain. The body is therefore wrapped in a valve <em>before</em> the pipe ever sees
 * it — transparent until flipped, and deaf to the pipe afterwards.
 */
final class EarlyResponseDrain implements ProxyInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(EarlyResponseDrain.class);

    /**
     * How long the drain may sit with zero progress before the connection closes — the same
     * interval, for the same reason, as the member-side drain's ({@link HttpBodyLimit}): a
     * healthy drain moves in milliseconds, and a wedged one is only diagnosable while its
     * counters are still warm.
     */
    private static final long DRAIN_STALL_MILLIS = 5_000;

    /** The context attachment carrying the request's valve between the two interceptor legs. */
    private static final String VALVE = "tesseraql.relay.uploadValve";

    @Override
    public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        ProxyRequest proxied = context.request();
        Body body = proxied.getBody();
        // Zero-length bodies included: GET and HEAD were already given a definite zero by the
        // interceptor before this one, and an upload that declared zero bytes has nothing a
        // drain could unblock.
        if (body != null && body.length() != 0 && !proxied.proxiedRequest().isEnded()) {
            UploadValve valve = new UploadValve(proxied.proxiedRequest(), body.stream());
            proxied.setBody(Body.body(valve, body.length(), body.mediaType()));
            context.set(VALVE, valve);
        }
        return context.sendRequest();
    }

    @Override
    public Future<Void> handleProxyResponse(ProxyContext context) {
        UploadValve valve = context.get(VALVE, UploadValve.class);
        if (valve == null || context.request().proxiedRequest().isEnded()) {
            return context.sendResponse();
        }
        // The proxy's callbacks run on the front request's event loop (the relay's stated
        // execution model), so the owner is always resolvable here.
        valve.flip(Vertx.currentContext().owner());
        return context.sendResponse().andThen(relayed -> resetOrigin(context));
    }

    /**
     * Releases the origin leg once the early answer is relayed: the member is owed the rest of a
     * body that will never be forwarded, and a reset turns its own polite drain into a closed
     * stream its handlers see immediately. Deferred until after the relay on purpose — a reset
     * closes the connection, and closing it with the answer's bytes still unread is the TCP
     * reset that destroys the very response this class exists to deliver.
     */
    private static void resetOrigin(ProxyContext context) {
        ProxyResponse response = context.response();
        HttpClientResponse origin = response == null ? null : response.proxiedResponse();
        if (origin != null) {
            origin.request().reset();
        }
    }

    /**
     * The front request's body stream, owned by this class rather than by the proxy's pipe.
     *
     * <p>Transparent until {@link #flip(Vertx) flipped}: every handler and every flow-control
     * call is forwarded to the real stream, so the pipe behaves exactly as if it held the stream
     * itself. Flipping installs the drain directly on the source and turns the wrapper inert —
     * the pipe's later cleanup (nulling handlers, resuming the source) lands on the valve and
     * goes nowhere, which is what keeps the drain attached however the abandoned pipe completes.
     */
    static final class UploadValve implements ReadStream<Buffer> {

        private final HttpServerRequest front;
        private final ReadStream<Buffer> source;
        /** Written and read on the front request's event loop only. */
        private boolean flipped;

        UploadValve(HttpServerRequest front, ReadStream<Buffer> source) {
            this.front = front;
            this.source = source;
        }

        /**
         * Takes the upload away from the pipe: from here on the body is read freely, counted
         * and discarded, so the caller's blocked write drains no matter what the origin
         * request's write queue does. Watched exactly like the member-side drain — zero
         * progress across a full interval logs the counters and closes the connection, because
         * politeness has already failed on a wedged stream and a prompt close is the one answer
         * the caller can still see.
         */
        void flip(Vertx vertx) {
            if (flipped) {
                return;
            }
            flipped = true;
            long declaredRemaining = declaredLength(front) - front.bytesRead();
            AtomicLong drained = new AtomicLong();
            AtomicLong lastProgress = new AtomicLong(0);
            long watchdog = vertx.setPeriodic(DRAIN_STALL_MILLIS, timer -> {
                if (front.isEnded()) {
                    vertx.cancelTimer(timer);
                    return;
                }
                long seen = drained.get();
                if (seen == lastProgress.getAndSet(seen)) {
                    vertx.cancelTimer(timer);
                    LOG.warn("Early-response drain stalled at the front door: {} byte(s)"
                            + " discarded of {} the caller still owed, {} read by the"
                            + " transport, no progress across a {} ms interval; closing the"
                            + " connection", seen, declaredRemaining, front.bytesRead(),
                            DRAIN_STALL_MILLIS);
                    front.connection().close();
                }
            });
            source.handler(discarded -> drained.addAndGet(discarded.length()));
            source.endHandler(ended -> vertx.cancelTimer(watchdog));
            source.exceptionHandler(broken -> vertx.cancelTimer(watchdog));
            // A caller that hangs up instead of finishing closes without the end or exception
            // handler firing — the member-side drain learned that the hard way; without this
            // the watchdog reads every routine abort as a stall.
            front.connection().closeHandler(closed -> vertx.cancelTimer(watchdog));
            source.resume();
        }

        /** The request's declared {@code Content-Length}, or {@code -1} when absent. */
        private static long declaredLength(HttpServerRequest request) {
            String declared = request.getHeader(io.vertx.core.http.HttpHeaders.CONTENT_LENGTH);
            if (declared == null) {
                return -1;
            }
            try {
                return Long.parseLong(declared.trim());
            } catch (NumberFormatException malformed) {
                return -1;
            }
        }

        // ------------------------------------------------ transparent until flipped

        @Override
        public UploadValve handler(Handler<Buffer> handler) {
            if (!flipped) {
                source.handler(handler);
            }
            return this;
        }

        @Override
        public UploadValve endHandler(Handler<Void> handler) {
            if (!flipped) {
                source.endHandler(handler);
            }
            return this;
        }

        @Override
        public UploadValve exceptionHandler(Handler<Throwable> handler) {
            if (!flipped) {
                source.exceptionHandler(handler);
            }
            return this;
        }

        @Override
        public UploadValve pause() {
            if (!flipped) {
                source.pause();
            }
            return this;
        }

        @Override
        public UploadValve resume() {
            if (!flipped) {
                source.resume();
            }
            return this;
        }

        @Override
        public UploadValve fetch(long amount) {
            if (!flipped) {
                source.fetch(amount);
            }
            return this;
        }
    }
}
