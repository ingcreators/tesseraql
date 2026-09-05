package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The typed answer for a request the transport refused before any route existed
 * (docs/http-edge-robustness.md decision 6).
 *
 * <p>{@link HttpBodyLimit} owns the router's 413 and was the only {@code errorHandler} in the
 * product. Every other router-level 400 fell to vertx-web's default: a body of literally
 * {@code Bad Request}, and {@code Unhandled exception in router} with a stack trace per attempt.
 * Form-decoder refusals land there — the body handler catches the decoder's exception and fails
 * the context with 400 — so a caller who posted too many fields, an over-size field, or a body
 * the decoder could not read got no code, no sentence and nothing a page could render.
 *
 * <p>The code is in the FIELD domain, not SEC. SEC's status arm ends {@code default -> 500}
 * because everything it does not enumerate is a server-side fault, so a caller-fault SEC code
 * would drag a hand-written case in another module into this one and land inside the status
 * ledger's scope. FIELD already answers 400, and this is the sibling of the malformed-body
 * refusal the declarative surface already raises — deliberately not spelled here, because the
 * reference generator reads a code mentioned in a javadoc as a site that raises it.
 *
 * <p><strong>The handler branches on the failure and never owns the status.</strong> vertx-web
 * raises its own 400s — a missing {@code Host} header among them — and answering those with
 * form vocabulary would be worse than the untyped body this replaces. For a failure it does not
 * recognise it returns without touching the response, and vertx-web finishes exactly as before
 * (verified: the default answer still goes out). But it logs first, because registering any
 * handler in this slot takes the else branch away from vertx-web's own error log, and this
 * handler then becomes the last thing that sees those requests at all.
 */
final class HttpBadRequest {

    private static final Logger LOG = LoggerFactory.getLogger(HttpBadRequest.class);

    private static final TqlErrorCode UNDECODABLE_FORM = new TqlErrorCode(TqlDomain.FIELD, 2012);

    private HttpBadRequest() {
    }

    /**
     * Installs the 400 error handler on the started platform router, beside the 413's.
     *
     * @param maxFormFields the declared field count, named in the refusal that cites it
     */
    static void install(RuntimeContext runtimeContext, int maxFormFields) {
        HttpEdgeBeans.router(runtimeContext).errorHandler(400,
                ctx -> answer(ctx, maxFormFields));
    }

    /**
     * A throw inside an error handler is swallowed by vertx-web as "Error in error handler" and
     * the caller silently gets the reason phrase, so the whole body is wrapped. The one request
     * that makes this concrete is {@code GET /%zz}: the handler runs with a null failure, a
     * status code of -1, and a normalized path that throws.
     */
    static void answer(RoutingContext ctx, int maxFormFields) {
        try {
            respond(ctx, maxFormFields);
        } catch (RuntimeException unexpected) {
            LOG.warn("The router's 400 handler failed; the caller gets the transport's own"
                    + " answer instead", unexpected);
        }
    }

    private static void respond(RoutingContext ctx, int maxFormFields) {
        // A decoder limit can trip during the 413's drain, so the response may already be gone.
        if (ctx.response().ended()) {
            return;
        }
        String reason = reasonFor(ctx.failure(), maxFormFields);
        if (reason == null) {
            // Not this handler's fault to name. Logged once because taking over this slot took
            // the else branch away from vertx-web's own error log, and only the raw path is
            // read: normalizedPath() throws for exactly the request most likely to land here.
            LOG.warn("Router-level 400 on {} {}: {}", ctx.request().method(),
                    ctx.request().path(),
                    ctx.failure() == null ? "no failure recorded" : ctx.failure());
            return;
        }
        // 400 is hard-coded rather than read from ctx.statusCode(), which is -1 when the failure
        // came from route matching rather than from a fail(int) call.
        boolean htmx = "true".equals(ctx.request().getHeader("HX-Request"));
        ctx.response()
                .setStatusCode(400)
                .putHeader("Content-Type", htmx
                        ? "text/html; charset=utf-8"
                        : "application/json; charset=utf-8")
                // The sentence is written inline at the raise site, not built into a local
                // first: the reference generator reads a code's meaning from the literals
                // beside it, and hoisting this one made the published meaning read
                // "application/json; charset=utf-8".
                .end(htmx
                        ? ErrorFragments.fieldErrors(UNDECODABLE_FORM,
                                "That form could not be read.", reason)
                        : io.tesseraql.core.error.ErrorEnvelope.json(UNDECODABLE_FORM,
                                "This form could not be decoded: " + reason));
    }

    /**
     * Which bound this failure crossed, or null when the failure is not the decoder's.
     *
     * <p>Four shapes reach this slot, and each branch names only what it knows. Matched by simple
     * name on purpose: the alternative is compiling this module against
     * {@code io.netty.handler.codec.http.multipart}, an artifact no module declares and nothing
     * else in the repository imports.
     *
     * <p>Two of them arrive with a null message because their only constructor is no-arg. The
     * attribute bound arrives as a bare {@code java.io.IOException}, unwrapped by the body
     * handler from a wrapper that carried the cause. All four were reproduced against
     * vertx-core 5.1.6 and netty-codec-http 4.2.17.
     */
    private static String reasonFor(Throwable failure, int maxFormFields) {
        if (failure == null) {
            return null;
        }
        return switch (failure.getClass().getSimpleName()) {
            case "TooManyFormFieldsException" -> "it carries more fields than"
                    + " tesseraql.http.maxFormFields allows (" + maxFormFields + ")";
            // The attribute-size bound, which is derived from tesseraql.http.maxBodyBytes rather
            // than configured separately, so that is the key worth naming.
            case "IOException" -> "one of its fields is larger than"
                    + " tesseraql.http.maxBodyBytes allows";
            // NOT "the body was too large": this bound is the undecoded remainder, so what it
            // says is that no field delimiter turned up within it.
            case "TooLongFormFieldException" -> "the decoder read past its buffer without"
                    + " finding the end of a field";
            // No bound was crossed at all — the bytes are not a form.
            case "ErrorDataDecoderException" -> "it is not valid"
                    + " application/x-www-form-urlencoded or multipart/form-data";
            default -> null;
        };
    }
}
