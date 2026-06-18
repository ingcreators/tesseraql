package io.tesseraql.mcp;

import java.util.List;

/**
 * The outcome of a {@code prompts/get}: an optional {@code description} and the ordered
 * {@link Message messages} the server hands the agent to seed (or run) a conversation. Each message
 * has a {@code role} ({@code user} or {@code assistant}) and text content.
 *
 * @param description a short summary of the rendered prompt, or null
 * @param messages    the messages, in order (never null; empty when there are none)
 */
public record McpPromptResult(String description, List<Message> messages) {

    public McpPromptResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** A single-message result: one {@code user} message with the given text. */
    public static McpPromptResult user(String text) {
        return new McpPromptResult(null, List.of(new Message("user", text)));
    }

    /** A single-message result with a description and one {@code user} message. */
    public static McpPromptResult user(String description, String text) {
        return new McpPromptResult(description, List.of(new Message("user", text)));
    }

    /** One prompt message: a {@code role} ({@code user}/{@code assistant}) and its text content. */
    public record Message(String role, String text) {
    }
}
