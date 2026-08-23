package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.atomic.AtomicLong;

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
 * and the connection closes.
 */
final class HttpBodyLimit {

    /** TQL-SEC-4150: the request body exceeds {@code tesseraql.http.maxBodyBytes} (HTTP 413). */
    private static final TqlErrorCode BODY_TOO_LARGE = new TqlErrorCode(TqlDomain.SEC, 4150);

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
            // Discard the rest of the upload, up to one more limit's worth; the body handler
            // stopped reading when it refused, and an unread stream is the wedge.
            AtomicLong drained = new AtomicLong();
            request.handler(remaining -> {
                if (drained.addAndGet(remaining.length()) > Math.max(maxBodyBytes, 0)) {
                    request.connection().close();
                }
            });
            request.endHandler(ended -> {
            });
            request.exceptionHandler(broken -> {
            });
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
}
