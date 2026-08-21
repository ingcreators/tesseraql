package io.tesseraql.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * The three things every hand-written HTTP surface needs, held by the runtime rather than by a
 * Camel component (docs/http-edge.md decision 1).
 *
 * <p>They used to come from {@code camel-platform-http-vertx}: the router from
 * {@code VertxPlatformHttpRouter.lookup}, the body handler from that router, and the header filter
 * from the {@code platform-http} component. Nothing routes through that component any more — the
 * REST DSL that created its consumers is gone — so the last thing it was providing was a place to
 * keep three objects. They are kept here instead, under names, which is what a registry is for.
 */
final class HttpEdgeBeans {

    /** The router every surface mounts on. */
    static final String ROUTER = "tesseraqlHttpRouter";

    /** The body handler that parses a request before a route sees it. */
    static final String BODY_HANDLER = "tesseraqlHttpBodyHandler";

    /** Which headers cross the boundary, in either direction. */

    private HttpEdgeBeans() {
    }

    /** The router this runtime serves on. */
    static Router router(RuntimeContext context) {
        return context.lookup(ROUTER, Router.class);
    }

    /** The body handler, configured once and shared by every route that can carry a body. */
    static BodyHandler bodyHandler(RuntimeContext context) {
        return context.lookup(BODY_HANDLER, BodyHandler.class);
    }

    /**
     * The body handler this runtime uses, built to the settings the platform-http consumer used to
     * apply: uploads handled and deleted when the exchange ends, form attributes merged, the body
     * buffer preallocated. Stated here because they are now this framework's defaults rather than
     * another component's.
     */
    static BodyHandler newBodyHandler() {
        return BodyHandler.create()
                .setHandleFileUploads(true)
                .setDeleteUploadedFilesOnEnd(true)
                .setMergeFormAttributes(true)
                .setPreallocateBodyBuffer(true);
    }
}
