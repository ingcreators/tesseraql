package io.tesseraql.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One MCP prompt: a server-offered, parameterized message template the connecting agent surfaces to
 * its model (for example as an IDE slash command). It has a programmatic {@code name}, a human
 * {@code title} and {@code description}, declared {@code arguments}, and an {@link McpPromptHandler}
 * that renders the messages for a given set of argument values.
 *
 * <p>Prompts are the third MCP primitive (alongside tools and resources). Unlike a tool, a prompt
 * carries no privilege - it only produces text - so it is the natural home for a guided workflow
 * (describe -&gt; draft -&gt; preview -&gt; apply) whose individual steps are the existing,
 * separately-gated tools.
 */
public final class McpPrompt {

    private final String name;
    private final String title;
    private final String description;
    private final List<Argument> arguments;
    private final McpPromptHandler handler;

    private McpPrompt(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.title = builder.title;
        this.description = builder.description;
        this.arguments = List.copyOf(builder.arguments);
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

    public List<Argument> arguments() {
        return arguments;
    }

    public McpPromptHandler handler() {
        return handler;
    }

    /** One declared prompt argument: its {@code name}, a {@code description}, and whether required. */
    public record Argument(String name, String description, boolean required) {
    }

    /** Fluent builder for an {@link McpPrompt}. */
    public static final class Builder {

        private final String name;
        private String title;
        private String description;
        private final List<Argument> arguments = new ArrayList<>();
        private McpPromptHandler handler;

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

        /** Declares an argument the agent may supply (and must, when {@code required}). */
        public Builder argument(String name, String description, boolean required) {
            arguments.add(new Argument(name, description, required));
            return this;
        }

        public Builder handler(McpPromptHandler handler) {
            this.handler = handler;
            return this;
        }

        public McpPrompt build() {
            return new McpPrompt(this);
        }
    }
}
