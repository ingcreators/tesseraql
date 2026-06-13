package io.tesseraql.runtime;

import io.tesseraql.mcp.McpHttpHandler;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves an application's declared MCP tools over the Streamable HTTP transport at
 * {@code /_tesseraql/mcp} (roadmap Phase 24 follow-on), bridging the platform-http request to the
 * transport-agnostic {@link McpHttpHandler}. {@code initialize} mints a session the client echoes;
 * {@code POST} carries one JSON-RPC message; {@code DELETE} ends the session. Each tool runs its own
 * route security, so there is no transport-level auth gate - discovery is open and a tool that
 * declares a policy enforces it on call.
 */
final class McpRouteBuilder extends RouteBuilder {

    private final McpHttpHandler handler;

    McpRouteBuilder(McpHttpHandler handler) {
        this.handler = handler;
    }

    @Override
    public void configure() {
        // Each verb routes to its own direct endpoint (one shared bridge): a single direct target
        // for all three would collide on the derived route id.
        rest().post("/_tesseraql/mcp").to("direct:mcp.endpoint.post");
        rest().get("/_tesseraql/mcp").to("direct:mcp.endpoint.get");
        rest().delete("/_tesseraql/mcp").to("direct:mcp.endpoint.delete");

        Processor bridge = bridge();
        from("direct:mcp.endpoint.post").routeId("mcp.endpoint.post").process(bridge);
        from("direct:mcp.endpoint.get").routeId("mcp.endpoint.get").process(bridge);
        from("direct:mcp.endpoint.delete").routeId("mcp.endpoint.delete").process(bridge);
    }

    private Processor bridge() {
        return exchange -> {
            McpHttpHandler.Request request = new McpHttpHandler.Request(
                    exchange.getMessage().getHeader(Exchange.HTTP_METHOD, "POST", String.class),
                    exchange.getMessage().getHeader("Authorization", String.class),
                    exchange.getMessage().getHeader(McpHttpHandler.SESSION_HEADER, String.class),
                    exchange.getMessage().getHeader("MCP-Protocol-Version", String.class),
                    exchange.getMessage().getBody(String.class));
            McpHttpHandler.Response response = handler.handle(request);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, response.status());
            response.headers()
                    .forEach((name, value) -> exchange.getMessage().setHeader(name, value));
            exchange.getMessage().setBody(response.body());
        };
    }
}
