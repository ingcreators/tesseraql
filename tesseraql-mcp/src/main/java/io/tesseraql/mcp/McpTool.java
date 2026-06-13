package io.tesseraql.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * One MCP tool: its programmatic {@code name}, a human {@code title} and {@code description}, the
 * JSON Schema for its arguments ({@code inputSchema}), and the {@link McpToolHandler} that runs it.
 *
 * <p>The abstraction is deliberately use-case-neutral. A dev-tool tool wraps a framework service
 * (scaffold, lint, run tests); a future app-declared tool will wrap a 2-way-SQL query or command -
 * both are just a name, a schema, and a handler, so the runtime can compile app YAML into this same
 * type (roadmap Phase 24).
 */
public final class McpTool {

    private final String name;
    private final String title;
    private final String description;
    private final ObjectNode inputSchema;
    private final McpToolHandler handler;

    private McpTool(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.title = builder.title;
        this.description = builder.description;
        this.inputSchema = builder.inputSchema != null
                ? builder.inputSchema
                : McpSchema.object().build();
        this.handler = Objects.requireNonNull(builder.handler, "handler");
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public ObjectNode inputSchema() {
        return inputSchema;
    }

    public McpToolHandler handler() {
        return handler;
    }

    /** Fluent builder for an {@link McpTool}. */
    public static final class Builder {

        private final String name;
        private String title;
        private String description;
        private ObjectNode inputSchema;
        private McpToolHandler handler;

        private Builder(String name) {
            this.name = name;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputSchema(ObjectNode inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder inputSchema(McpSchema inputSchema) {
            this.inputSchema = inputSchema.build();
            return this;
        }

        public Builder handler(McpToolHandler handler) {
            this.handler = handler;
            return this;
        }

        public McpTool build() {
            return new McpTool(this);
        }
    }
}
