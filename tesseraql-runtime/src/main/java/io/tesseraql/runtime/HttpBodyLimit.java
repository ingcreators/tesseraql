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
        if (maxBodyBytes < 0) {
            // -1 opts the framework limit out: the body handler then never fires a 413, and
            // an installed handler would only hijack an app's own fail(413) with an envelope
            // claiming a bound that does not exist.
            return;
        }
        HttpEdgeBeans.router(runtimeContext).errorHandler(413,
                ctx -> refuse(ctx, maxBodyBytes));
    }

    private static void refuse(RoutingContext ctx, long maxBodyBytes) {
        HttpServerRequest request = ctx.request();
        // Whether the 413's write completed. The still-open silence shape has a flush-deferral
        // hypothesis — the refusal queued but never flushed — and the one cheap probe for it
        // is this bit, reported by the watchdog and by the write's own failure log.
        java.util.concurrent.atomic.AtomicReference<String> refusalWrite = new java.util.concurrent.atomic.AtomicReference<>(
                "in flight");
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
            // Zero bytes across the first interval is already the wedge — a live drain moves
            // in milliseconds — so progress starts counted from the drain's own start rather
            // than from a sentinel that granted every wedge one free interval.
            AtomicLong lastProgress = new AtomicLong(0);
            long watchdog = ctx.vertx().setPeriodic(DRAIN_STALL_MILLIS, timer -> {
                if (request.isEnded()) {
                    ctx.vertx().cancelTimer(timer);
                    return;
                }
                long seen = drained.get();
                if (seen == lastProgress.getAndSet(seen)) {
                    ctx.vertx().cancelTimer(timer);
                    // bytesRead beside drained separates a delivery wedge (the transport
                    // stopped handing bytes over: both frozen) from a receive wedge (bytes
                    // arrive, the handler never runs: bytesRead moves alone); the 413-write
                    // state is the flush-deferral probe.
                    LOG.warn("Over-limit drain stalled: {} of {} remaining declared byte(s)"
                            + " drained, {} read by the transport, no progress across a {} ms"
                            + " interval; 413 write {}; closing the connection (bound {})",
                            seen, declaredRemaining, request.bytesRead(), DRAIN_STALL_MILLIS,
                            refusalWrite.get(), bound);
                    request.connection().close();
                }
            });
            request.handler(remaining -> {
                if (drained.addAndGet(remaining.length()) > bound) {
                    // The close is the bound's answer to a liar; the watchdog stands down so
                    // the same cut is not also reported as a stall.
                    ctx.vertx().cancelTimer(watchdog);
                    request.connection().close();
                }
            });
            request.endHandler(ended -> ctx.vertx().cancelTimer(watchdog));
            request.exceptionHandler(broken -> ctx.vertx().cancelTimer(watchdog));
            // A client that reads its 413 and aborts the upload with a clean FIN closes the
            // connection without the request's end or exception handler ever firing (Vert.x 5
            // notifies only the response in progress on close, and the decoder treats a
            // premature end of a fixed-length body as a silent reset) — without this hook the
            // next tick read every routine abort as a stall and double-closed a dead
            // connection, drowning the one WARN the real wedge needs to stay visible.
            request.connection().closeHandler(closed -> ctx.vertx().cancelTimer(watchdog));
            request.resume();
        }
        if (ctx.response().ended()) {
            return;
        }
        // An htmx upload gets the refusal as a fragment it can actually show. This handler runs
        // before any route context exists, so it says the limit and names the configuration key
        // rather than the route — but a browser posting a file over the cap used to render
        // nothing at all: htmx declines to swap a 4xx whose body carries no allowance marker,
        // and a router-level JSON envelope carries none (docs/csv-import.md decision 7).
        boolean htmx = "true".equals(ctx.request().getHeader("HX-Request"));
        ctx.response()
                .setStatusCode(413)
                .putHeader("Content-Type", htmx
                        ? "text/html; charset=utf-8"
                        : "application/json; charset=utf-8")
                // The code, not a message built from the request — the same envelope
                // discipline the admission gate's refusal follows.
                .end(htmx
                        ? overLimitFragment(maxBodyBytes)
                        : io.tesseraql.core.error.ErrorEnvelope.json(BODY_TOO_LARGE,
                                "The request body exceeds tesseraql.http.maxBodyBytes ("
                                        + maxBodyBytes + " bytes)"))
                .onComplete(written -> {
                    refusalWrite.set(written.succeeded()
                            ? "completed"
                            : "failed: " + written.cause());
                    if (written.failed()) {
                        LOG.warn("The over-limit 413's write failed: {}",
                                String.valueOf(written.cause()));
                    }
                });
    }

    /**
     * The over-limit refusal as markup: the field-errors fragment every other refusal answers
     * with, carrying the marker the bootstrap's swap allowance already reads. Deliberately not
     * the import surface's own marker — this handler refuses any over-cap body, not only an
     * upload, and a fragment that named itself a report would be lying to whatever posted.
     *
     * <p>English and route-free, because a pre-route handler has neither a negotiated locale nor
     * a route to read one from. What it honestly knows is the bound it enforced and the key that
     * sets it, so that is what it says.
     */
    private static String overLimitFragment(long maxBodyBytes) {
        return "<div class=\"hc-alert\" data-variant=\"error\" role=\"alert\""
                + " data-hc-field-errors data-error-code=\"" + BODY_TOO_LARGE + "\">"
                + "<p class=\"hc-alert__title\">That request is too large.</p>"
                + "<p class=\"hc-alert__body\">The limit is " + maxBodyBytes
                + " bytes (tesseraql.http.maxBodyBytes).</p></div>";
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
