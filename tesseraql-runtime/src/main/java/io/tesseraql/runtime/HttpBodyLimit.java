package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The typed refusal behind {@code tesseraql.http.maxBodyBytes}, and the stream discipline that
 * makes it safe to send mid-upload.
 *
 * <p>The body handler refuses an over-limit request by failing the context with 413 — while the
 * client is, by definition, still writing the rest of the body. Left to the router's default
 * error handling, the 413 went out untyped and the rest of the request was never read: once the
 * transport's window filled, a client blocked in its own write never saw the answer and hung
 * until something else timed out — five minutes of CI, three times, before a stack trace said
 * where. Closing the connection instead is no answer either: the relay in front reads an origin
 * that resets mid-request as a failed origin and turns the app's own 413 into a 502.
 *
 * <p>So the refusal <em>drains</em>: the remaining body is read and discarded, which unblocks
 * the client's write, lets the 413 arrive, and leaves the connection reusable. The drain is
 * bounded — a stream still going after another full limit's worth is past what politeness buys,
 * and the connection closes. And the drain is <em>watched</em>: one that stops making progress
 * has wedged the client just as surely as never draining at all, so after an interval of zero
 * progress the counters are logged and the connection closes ({@link #DRAIN_STALL_MILLIS}).
 */
final class HttpBodyLimit {

    private static final Logger LOG = LoggerFactory.getLogger(HttpBodyLimit.class);

    /** TQL-SEC-4150: the request body exceeds {@code tesseraql.http.maxBodyBytes} (HTTP 413). */
    private static final TqlErrorCode BODY_TOO_LARGE = new TqlErrorCode(TqlDomain.SEC, 4150);

    /**
     * How long the drain may sit with zero progress before the connection closes. A healthy
     * drain moves in milliseconds; an upload trickling at even one byte per interval keeps its
     * connection. Only a stream making no progress at all — the wedge, whatever its cause —
     * gets cut, and well inside any client's or CI's own timeout so the failure is attributed
     * here, not to the caller's clock.
     */
    private static final long DRAIN_STALL_MILLIS = 5_000;

    private HttpBodyLimit() {
    }

    /**
     * Installs the 413 error handler on the started platform router — the same post-start hook
     * the admission gate uses, because the router exists only after the HTTP server service
     * started.
     */
    static void install(RuntimeContext runtimeContext, long maxBodyBytes) {
        HttpEdgeBeans.router(runtimeContext).errorHandler(413,
                ctx -> refuse(ctx, maxBodyBytes));
    }

    private static void refuse(RoutingContext ctx, long maxBodyBytes) {
        HttpServerRequest request = ctx.request();
        if (!request.isEnded()) {
            // Discard the rest of the upload; the body handler stopped reading when it
            // refused, and an unread stream is the wedge. The bound is what the client
            // DECLARED it still owes, not a flat limit's worth: the body handler refuses a
            // declared over-limit length before reading any of it, so the remainder is the
            // whole declaration — a flat bound closed the connection mid-upload, and closing
            // with unread data is a TCP reset that can destroy the 413 already sent (the
            // 61-second gateway stall and the broken-pipe flake were both that reset winning
            // the race). A liar — a stream still going past its own declaration — and an
            // undeclared (chunked) stream keep the flat bound; politeness covers what was
            // declared, never more.
            AtomicLong drained = new AtomicLong();
            long declaredRemaining = declaredLength(request) - request.bytesRead();
            long bound = Math.max(Math.max(maxBodyBytes, 0), declaredRemaining);
            request.handler(remaining -> {
                if (drained.addAndGet(remaining.length()) > bound) {
                    request.connection().close();
                }
            });
            // The drain itself is watched. CI produced a third shape of this flake with the
            // declared-remainder bound already in place: the client timed out after sixty
            // seconds of silence on a connection the server kept open — which means the drain
            // stopped consuming and nothing noticed, because an early response is invisible to
            // a JDK HTTP/1.1 client until its upload completes (verified against a raw socket:
            // a flushed 413 on an unread stream surfaces as the client's own timeout, nothing
            // else). The stall's cause is not established, so the watchdog does not pretend to
            // prevent it: zero progress across a full interval logs the counters this
            // diagnosis needed and closes the connection — politeness has already failed on a
            // wedged stream, and a prompt close is the one answer the client can still see.
            AtomicLong lastProgress = new AtomicLong(-1);
            long watchdog = ctx.vertx().setPeriodic(DRAIN_STALL_MILLIS, timer -> {
                if (request.isEnded()) {
                    ctx.vertx().cancelTimer(timer);
                    return;
                }
                long seen = drained.get();
                if (seen == lastProgress.getAndSet(seen)) {
                    ctx.vertx().cancelTimer(timer);
                    LOG.warn("Over-limit drain stalled: {} of {} remaining declared byte(s)"
                            + " drained, no progress for {} ms; closing the connection"
                            + " (413 already sent, bound {})",
                            seen, declaredRemaining, DRAIN_STALL_MILLIS, bound);
                    request.connection().close();
                }
            });
            request.endHandler(ended -> ctx.vertx().cancelTimer(watchdog));
            request.exceptionHandler(broken -> ctx.vertx().cancelTimer(watchdog));
            request.resume();
        }
        if (ctx.response().ended()) {
            return;
        }
        ctx.response()
                .setStatusCode(413)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                // The code, not a message built from the request — the same envelope
                // discipline the admission gate's refusal follows.
                .end(io.tesseraql.core.error.ErrorEnvelope.json(BODY_TOO_LARGE,
                        "The request body exceeds tesseraql.http.maxBodyBytes ("
                                + maxBodyBytes + " bytes)"));
    }

    /** The request's declared {@code Content-Length}, or {@code -1} when absent or malformed. */
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
}
