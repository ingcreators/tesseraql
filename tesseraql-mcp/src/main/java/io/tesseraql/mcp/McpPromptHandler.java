package io.tesseraql.mcp;

import java.util.Map;

/**
 * Builds the messages for one {@link McpPrompt} get. The handler receives the prompt's argument
 * values (a string-keyed map, never null - an empty map when the caller sent none) and returns the
 * {@link McpPromptResult} the server sends back for {@code prompts/get}.
 *
 * <p>A prompt is a server-offered, parameterized message template the connecting agent surfaces to
 * its model (an IDE slash command, say). It carries no privilege of its own - it only returns text -
 * so a handler does not authenticate; any action the messages suggest still runs through the normal
 * tool calls, with their own auth and gating.
 */
@FunctionalInterface
public interface McpPromptHandler {

    McpPromptResult handle(Map<String, String> arguments);
}
