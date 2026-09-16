package io.tesseraql.runtime;

import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.mcp.McpHttpHandler;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.Step;

/**
 * Serves an application's declared MCP tools over the Streamable HTTP transport at
 * {@code /_tesseraql/mcp} (roadmap Phase 24 follow-on), bridging the platform-http request to the
 * transport-agnostic {@link McpHttpHandler}. {@code initialize} mints a session the client echoes;
 * {@code POST} carries one JSON-RPC message; {@code DELETE} ends the session. Each tool runs its own
 * route security, so the transport-level gate is {@code tesseraql.mcp.auth} (public by default)
 * - discovery is open and a tool that declares a policy enforces it on call. The handler judges
 * the caller's {@code Origin} and {@code Content-Type} (docs/audit-low-leads.md, G1); the bridge
 * hands both through, and the runtime allows its own external origin beside loopback.
 */
final class McpRoutes {

    /** The path every application's MCP surface sits at, below its base path. */
    static final String PATH = "/_tesseraql/mcp";

    private final McpHttpHandler handler;

    McpRoutes(McpHttpHandler handler) {
        this.handler = handler;
    }

    /**
     * The application's MCP resource identifier (RFC 8707) as the wire spells it:
     * {@code <origin><base path>/_tesseraql/mcp}, percent-encoded. A resource is a URI, and it
     * was built raw everywhere — a Japanese application's name is not a URI, and the one site
     * that crosses the wire as a header, the challenge, folded it to {@code ?}
     * (docs/audit-low-leads.md, unfiled 48). ASCII is its own wire form, so every ASCII name
     * reads exactly as before.
     */
    static String resource(String externalOrigin, String basePath) {
        return io.tesseraql.core.http.PercentEncoding.uriLiteral(
                (externalOrigin == null ? "" : externalOrigin)
                        + (basePath == null ? "" : basePath) + PATH);
    }

    /** The RFC 9728 document's address for that resource, spelled the same way. */
    static String metadataUrl(String externalOrigin, String basePath) {
        return io.tesseraql.core.http.PercentEncoding.uriLiteral(
                externalOrigin + "/.well-known/oauth-protected-resource"
                        + (basePath == null ? "" : basePath) + PATH);
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
        HttpMounts.of(context).mount("POST", PATH, "mcp.endpoint.post");
        HttpMounts.of(context).mount("GET", PATH, "mcp.endpoint.get");
        HttpMounts.of(context).mount("DELETE", PATH, "mcp.endpoint.delete");

        Step bridge = bridge();
        pipelines.pipeline("mcp.endpoint.post").process(bridge);
        pipelines.pipeline("mcp.endpoint.get").process(bridge);
        pipelines.pipeline("mcp.endpoint.delete").process(bridge);
    }

    private Step bridge() {
        return exchange -> {
            McpHttpHandler.Request request = new McpHttpHandler.Request(
                    exchange.request().method() == null ? "POST" : exchange.request().method(),
                    exchange.request().header("Authorization"),
                    exchange.request().header(McpHttpHandler.SESSION_HEADER),
                    exchange.request().header(McpHttpHandler.PROTOCOL_VERSION_HEADER),
                    exchange.request().header("Origin"),
                    exchange.request().header("Content-Type"),
                    exchange.getBody(String.class));
            McpHttpHandler.Response response = handler.handle(request);
            exchange.response().status(response.status());
            response.headers()
                    .forEach((name, value) -> exchange.response().header(name, value));
            exchange.setBody(response.body());
        };
    }
}
