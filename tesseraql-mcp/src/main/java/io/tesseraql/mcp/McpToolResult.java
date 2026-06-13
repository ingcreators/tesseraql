package io.tesseraql.mcp;

import java.util.List;

/**
 * The result of a {@code tools/call}: ordered content blocks, an {@code isError} flag, and an
 * optional structured value (serialized to MCP {@code structuredContent}).
 *
 * <p>Content is intentionally minimal - {@code text} blocks only - but modeled as a list of typed
 * blocks so richer block kinds (resource links, and MCP Apps UI in particular) can be added later
 * without changing this type's shape.
 */
public final class McpToolResult {

    private final List<Content> content;
    private final boolean error;
    private final Object structured;

    private McpToolResult(List<Content> content, boolean error, Object structured) {
        this.content = List.copyOf(content);
        this.error = error;
        this.structured = structured;
    }

    /** A successful result carrying a single text block. */
    public static McpToolResult text(String text) {
        return new McpToolResult(List.of(new Content("text", text)), false, null);
    }

    /**
     * A successful result whose value is serialized both as {@code structuredContent} and as a
     * pretty-printed text block (the text mirror keeps clients that ignore structured content
     * working - MCP recommends returning both).
     */
    public static McpToolResult json(Object value) {
        return new McpToolResult(List.of(), false, value);
    }

    /** A tool-level failure: {@code isError} with a single explanatory text block. */
    public static McpToolResult error(String message) {
        return new McpToolResult(List.of(new Content("text", message)), true, null);
    }

    public List<Content> content() {
        return content;
    }

    public boolean isError() {
        return error;
    }

    /** The structured value to serialize as {@code structuredContent}, or null. */
    public Object structured() {
        return structured;
    }

    /** A single content block. Only {@code text} blocks are produced today. */
    public record Content(String type, String text) {
    }
}
