package io.tesseraql.mcp;

import java.util.Map;

/**
 * Builds the messages for one {@link McpPrompt} get. The handler receives the prompt's argument
 * values (a string-keyed map, never null - an empty map when the caller sent none) and the call's
 * {@link McpCallContext}, and returns the {@link McpPromptResult} the server sends back for
 * {@code prompts/get}.
 *
 * <p>A prompt is a server-offered, parameterized message template the connecting agent surfaces to
 * its model (an IDE slash command, say). A handler gets the same transport context a tool's does
 * because a served prompt may render from data: an application prompt runs its own route, with its
 * own authentication and authorization, exactly as a tool call does.
 */
@FunctionalInterface
public interface McpPromptHandler {

    McpPromptResult handle(Map<String, String> arguments, McpCallContext context);
}
