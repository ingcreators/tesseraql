package io.tesseraql.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A small builder for a JSON Schema {@code object} - the {@code inputSchema} an {@link McpTool}
 * advertises so a client knows a tool's parameters. Only the subset MCP tool inputs need
 * (object with typed, optionally-required properties) is modeled; richer schemas can be supplied
 * as a raw {@link ObjectNode} through {@link McpTool}.
 */
public final class McpSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode schema = MAPPER.createObjectNode();
    private final ObjectNode properties = MAPPER.createObjectNode();
    private final ArrayNode required = MAPPER.createArrayNode();

    private McpSchema() {
        schema.put("type", "object");
    }

    /** Starts an empty object schema (no properties - a tool that takes no arguments). */
    public static McpSchema object() {
        return new McpSchema();
    }

    /** Declares an optional property of the given JSON type with a description. */
    public McpSchema property(String name, String jsonType, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", jsonType);
        property.put("description", description);
        return this;
    }

    /** Declares a required property of the given JSON type with a description. */
    public McpSchema required(String name, String jsonType, String description) {
        property(name, jsonType, description);
        required.add(name);
        return this;
    }

    /** Builds the schema node. Empties ({@code properties}/{@code required}) are omitted. */
    public ObjectNode build() {
        ObjectNode result = schema.deepCopy();
        if (!properties.isEmpty()) {
            result.set("properties", properties.deepCopy());
        }
        if (!required.isEmpty()) {
            result.set("required", required.deepCopy());
        }
        return result;
    }
}
