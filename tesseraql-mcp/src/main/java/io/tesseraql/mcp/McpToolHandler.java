package io.tesseraql.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs one {@link McpTool} call. The handler receives the {@code arguments} object of the
 * {@code tools/call} request (never null - an empty object when the caller sent none) and returns
 * the result to send back.
 *
 * <p>A handler signals a tool-level failure either by returning {@link McpToolResult#error} or by
 * throwing: {@link McpServer} catches the throwable and turns it into an {@code isError} result, so
 * an agent sees the message and can correct course rather than the connection breaking (this is the
 * MCP contract - tool failures are results, not JSON-RPC protocol errors).
 *
 * <p>The {@link McpCallContext} carries transport metadata (the request's {@code Authorization}
 * header) so a handler can authenticate; dev-tool handlers ignore it.
 */
@FunctionalInterface
public interface McpToolHandler {

    McpToolResult handle(JsonNode arguments, McpCallContext context) throws Exception;
}
