package io.tesseraql.runtime;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.platform.http.vertx.HttpMessage;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpRouter;
import org.apache.camel.support.DefaultExchange;

/**
 * Serves one compiled route from a Vert.x handler, on a virtual thread (docs/http-edge.md
 * slice 1). A prototype, in test sources, beside the Camel edge rather than replacing it.
 *
 * <p>What it has to build is the part {@code camel-platform-http-vertx} builds today: an
 * {@link Exchange} the route's processors recognise, and a response out of the one they produce.
 * Both are small, and that is the finding — the consumer is what dispatches to the worker pool,
 * and the consumer is the only part being replaced.
 */
final class EdgeMount {

    /** After the admission gate, like every other surface mounted on the router. */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

    private EdgeMount() {
    }

    /** Mounts {@code routeId}'s pipeline at {@code path}, in addition to its Camel route. */
    static void install(CamelContext camelContext, int port, String routeId, String path)
            throws Exception {
        EdgePipeline pipeline = EdgePipeline.of(camelContext, routeId)
                .orElseThrow(() -> new IllegalStateException(
                        "Route '" + routeId + "' is not a plain processor chain"));
        pipeline.start();
        VertxPlatformHttpRouter router = VertxPlatformHttpRouter.lookup(camelContext,
                VertxPlatformHttpRouter.getRouterNameFromPort(port));
        router.route(HttpMethod.GET, path).order(AFTER_THE_GATE)
                .handler(ctx -> serve(camelContext, pipeline, ctx));
    }

    private static void serve(CamelContext camelContext, EdgePipeline pipeline,
            RoutingContext ctx) {
        Context connection = ctx.vertx().getOrCreateContext();
        Exchange exchange = request(camelContext, ctx);
        Thread.ofVirtual().name("tql-edge").start(() -> {
            pipeline.run(exchange);
            connection.runOnContext(reply -> respond(ctx, exchange));
        });
    }

    /** The exchange the route's processors expect, read off the request before leaving it. */
    private static Exchange request(CamelContext camelContext, RoutingContext ctx) {
        Exchange exchange = new DefaultExchange(camelContext);
        HttpMessage message = new HttpMessage(exchange, ctx.request(), ctx.response());
        exchange.setMessage(message);
        message.setHeader(Exchange.HTTP_METHOD, ctx.request().method().name());
        message.setHeader(Exchange.HTTP_URI, ctx.request().uri());
        message.setHeader(Exchange.HTTP_PATH, ctx.request().path());
        message.setHeader(Exchange.HTTP_QUERY, ctx.request().query());
        ctx.request().headers()
                .forEach(header -> message.setHeader(header.getKey(), header.getValue()));
        ctx.queryParams().forEach(param -> message.setHeader(param.getKey(), param.getValue()));
        ctx.pathParams().forEach(message::setHeader);
        message.setBody(ctx.body() == null ? null : ctx.body().asString());
        return exchange;
    }

    private static void respond(RoutingContext ctx, Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        ctx.response().setStatusCode(code == null ? 200 : code);
        if (contentType != null) {
            ctx.response().putHeader("Content-Type", contentType);
        }
        if (body == null) {
            ctx.response().end();
            return;
        }
        ctx.response().end(body instanceof byte[] bytes
                ? Buffer.buffer(bytes)
                : Buffer.buffer(exchange.getContext().getTypeConverter()
                        .convertTo(String.class, exchange, body)));
    }
}
