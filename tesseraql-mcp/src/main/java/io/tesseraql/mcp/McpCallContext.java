package io.tesseraql.mcp;

/**
 * Per-call context handed to a tool handler alongside the arguments: the transport metadata a tool
 * may need beyond its declared inputs. Today that is the request's {@code Authorization} header, so
 * an application-served tool can run its own authentication and authorization (the dev-tool handlers
 * ignore it). Kept a record so more transport context can be added without touching handlers.
 */
public record McpCallContext(String authorization) {

    /** No transport context - used by the stdio transport, which is single-user and trusted. */
    public static final McpCallContext EMPTY = new McpCallContext(null);
}
