package io.tesseraql.runtime;

import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.mcp.McpHttpHandler;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.Step;

/**
 * Serves an application's declared MCP tools over the Streamable HTTP transport at
 * {@code /_tesseraql/mcp} (roadmap Phase 24 follow-on), bridging the platform-http request to the
 * transport-agnostic {@link McpHttpHandler}. {@code initialize} mints a session the client echoes;
 * {@code POST} carries one JSON-RPC message; {@code DELETE} ends the session. Each tool runs its own
 * route security, so there is no transport-level auth gate - discovery is open and a tool that
 * declares a policy enforces it on call.
 */
final class McpRouteBuilder {

    private final McpHttpHandler handler;

    McpRouteBuilder(McpHttpHandler handler) {
        this.handler = handler;
    }

    void install(RuntimeContext context) {
        // The error envelope every other framework surface carries. These three had none: the
        // handler catches what it expects, and anything it did not left the caller holding an
        // open connection (docs/camel-removal.md slice 2b).
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(io.tesseraql.core.error.TqlException.class,
                                new io.tesseraql.compiler.binding.ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class,
                                new io.tesseraql.compiler.binding.ErrorResponseRenderer())));
        // Each verb answers on its own pipeline (one shared bridge): a single target for all
        // three would collide on the id.
        HttpMounts.mount(context, "POST", "/_tesseraql/mcp", "mcp.endpoint.post");
        HttpMounts.mount(context, "GET", "/_tesseraql/mcp", "mcp.endpoint.get");
        HttpMounts.mount(context, "DELETE", "/_tesseraql/mcp", "mcp.endpoint.delete");

        Step bridge = bridge();
        pipelines.pipeline("mcp.endpoint.post").process(bridge);
        pipelines.pipeline("mcp.endpoint.get").process(bridge);
        pipelines.pipeline("mcp.endpoint.delete").process(bridge);
    }

    private Step bridge() {
        return exchange -> {
            McpHttpHandler.Request request = new McpHttpHandler.Request(
                    exchange.getMessage().getHeader(Headers.HTTP_METHOD, "POST", String.class),
                    exchange.getMessage().getHeader("Authorization", String.class),
                    exchange.getMessage().getHeader(McpHttpHandler.SESSION_HEADER, String.class),
                    exchange.getMessage().getHeader("MCP-Protocol-Version", String.class),
                    exchange.getMessage().getBody(String.class));
            McpHttpHandler.Response response = handler.handle(request);
            exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, response.status());
            response.headers()
                    .forEach((name, value) -> exchange.getMessage().setHeader(name, value));
            exchange.getMessage().setBody(response.body());
        };
    }
}
