package io.tesseraql.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A transport-agnostic Model Context Protocol server: it turns one parsed JSON-RPC message into the
 * response to send back (or none, for a notification). It is stateless and immutable, so a single
 * instance can be shared across stdio and across concurrent HTTP sessions.
 *
 * <p>Supported methods: {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call},
 * and the {@code notifications/initialized} notification. Tool failures become {@code isError}
 * results (the MCP contract), not protocol errors, so an agent can read the message and retry.
 *
 * <p>Resources and prompts are not implemented yet; unknown methods return JSON-RPC
 * {@code method not found}. The dispatch is a method-keyed switch, so those surfaces (and the
 * app-declared tools of a later phase) slot in without reshaping this class.
 */
public final class McpServer {

    /** MCP revisions this server speaks; it echoes the client's if listed, else {@link #LATEST}. */
    private static final Set<String> SUPPORTED = Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    private static final String LATEST = "2025-06-18";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String name;
    private final String version;
    private final String instructions;
    private final Map<String, McpTool> tools;

    private McpServer(Builder builder) {
        this.name = builder.name;
        this.version = builder.version;
        this.instructions = builder.instructions;
        // LinkedHashMap, not Map.copyOf: tools/list must keep registration order (Map.copyOf
        // randomizes iteration order).
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tools));
    }

    public static Builder builder(String name, String version) {
        return new Builder(name, version);
    }

    /** Handles a request with no transport context (the stdio case). */
    public Optional<JsonNode> handle(JsonNode message) {
        return handle(message, McpCallContext.EMPTY);
    }

    /**
     * Handles one request or notification. Returns the response to send for a request, or empty for
     * a notification (no {@code id}) - the transport sends nothing in that case. The
     * {@link McpCallContext} (the request's auth header) is passed through to tool handlers.
     */
    public Optional<JsonNode> handle(JsonNode message, McpCallContext context) {
        if (message == null || !message.isObject()) {
            return Optional.of(error(null, INVALID_REQUEST, "Expected a JSON-RPC object"));
        }
        JsonNode id = message.get("id");
        JsonNode methodNode = message.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return id == null
                    ? Optional.empty()
                    : Optional.of(error(id, INVALID_REQUEST, "Missing method"));
        }
        String method = methodNode.asText();
        boolean notification = id == null;
        if (notification) {
            // The only notification we expect is notifications/initialized; nothing to answer.
            return Optional.empty();
        }
        JsonNode params = message.get("params");
        switch (method) {
            case "initialize" :
                return Optional.of(result(id, initialize(params)));
            case "ping" :
                return Optional.of(result(id, mapper.createObjectNode()));
            case "tools/list" :
                return Optional.of(result(id, toolsList()));
            case "tools/call" :
                return Optional.of(toolsCall(id, params, context));
            default :
                return Optional.of(error(id, METHOD_NOT_FOUND, "Unknown method: " + method));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params != null && params.hasNonNull("protocolVersion")
                ? params.get("protocolVersion").asText()
                : LATEST;
        String negotiated = SUPPORTED.contains(requested) ? requested : LATEST;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", name);
        serverInfo.put("version", version);
        if (instructions != null && !instructions.isBlank()) {
            result.put("instructions", instructions);
        }
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode array = result.putArray("tools");
        for (McpTool tool : tools.values()) {
            ObjectNode node = array.addObject();
            node.put("name", tool.name());
            if (tool.title() != null) {
                node.put("title", tool.title());
            }
            if (tool.description() != null) {
                node.put("description", tool.description());
            }
            node.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private JsonNode toolsCall(JsonNode id, JsonNode params, McpCallContext context) {
        if (params == null || !params.hasNonNull("name")) {
            return error(id, INVALID_PARAMS, "tools/call requires a tool name");
        }
        String toolName = params.get("name").asText();
        McpTool tool = tools.get(toolName);
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unknown tool: " + toolName);
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = mapper.createObjectNode();
        }
        McpToolResult outcome;
        try {
            outcome = tool.handler().handle(arguments, context);
        } catch (TqlException ex) {
            outcome = McpToolResult.error(ex.code() + ": " + ex.getMessage());
        } catch (Exception ex) {
            String detail = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            outcome = McpToolResult.error(detail);
        }
        return result(id, toolResult(outcome));
    }

    private ObjectNode toolResult(McpToolResult outcome) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        for (McpToolResult.Content block : outcome.content()) {
            ObjectNode node = content.addObject();
            node.put("type", block.type());
            node.put("text", block.text());
        }
        if (outcome.structured() != null) {
            JsonNode structured = mapper.valueToTree(outcome.structured());
            result.set("structuredContent", structured);
            if (outcome.content().isEmpty()) {
                content.addObject().put("type", "text").put("text", pretty(structured));
            }
        }
        result.put("isError", outcome.isError());
        return result;
    }

    private String pretty(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ex) {
            return node.toString();
        }
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = envelope(id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = envelope(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ObjectNode envelope(JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? mapper.nullNode() : id);
        return response;
    }

    /** Fluent builder for an {@link McpServer}. */
    public static final class Builder {

        private final String name;
        private final String version;
        private String instructions;
        private final Map<String, McpTool> tools = new LinkedHashMap<>();

        private Builder(String name, String version) {
            this.name = name;
            this.version = version;
        }

        /** Free-text guidance returned in {@code initialize}, shown to the connecting agent. */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder tool(McpTool tool) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
            return this;
        }

        public Builder tools(List<McpTool> tools) {
            List<McpTool> ordered = new ArrayList<>(tools);
            ordered.forEach(this::tool);
            return this;
        }

        public McpServer build() {
            return new McpServer(this);
        }
    }
}
