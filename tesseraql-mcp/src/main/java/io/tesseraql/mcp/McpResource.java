package io.tesseraql.mcp;

import java.util.Objects;

/**
 * One MCP resource: read-only context an agent attaches, addressed by a stable {@code uri}. It
 * carries a programmatic {@code name}, an optional human {@code title} and {@code description}, an
 * optional {@code mimeType}, and the {@link McpResourceReader} that produces its text on a
 * {@code resources/read}.
 *
 * <p>Like {@link McpTool}, the abstraction is use-case-neutral: a {@code uri}, some metadata, and a
 * reader. The runtime compiles a {@code query-json} definition under {@code mcp/} into this same
 * type, so an application's read-only data is served as an MCP resource alongside its tools
 * (roadmap Phase 24).
 */
public final class McpResource {

    private final String uri;
    private final String name;
    private final String title;
    private final String description;
    private final String mimeType;
    private final McpResourceReader reader;

    private McpResource(Builder builder) {
        this.uri = Objects.requireNonNull(builder.uri, "uri");
        this.name = Objects.requireNonNull(builder.name, "name");
        this.title = builder.title;
        this.description = builder.description;
        this.mimeType = builder.mimeType;
        this.reader = Objects.requireNonNull(builder.reader, "reader");
    }

    public static Builder builder(String uri, String name) {
        return new Builder(uri, name);
    }

    public String uri() {
        return uri;
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

    public String mimeType() {
        return mimeType;
    }

    public McpResourceReader reader() {
        return reader;
    }

    /** Fluent builder for an {@link McpResource}. */
    public static final class Builder {

        private final String uri;
        private final String name;
        private String title;
        private String description;
        private String mimeType;
        private McpResourceReader reader;

        private Builder(String uri, String name) {
            this.uri = uri;
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

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder reader(McpResourceReader reader) {
            this.reader = reader;
            return this;
        }

        public McpResource build() {
            return new McpResource(this);
        }
    }
}
