package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.UiSpec;
import java.nio.file.Path;

/**
 * An application-declared MCP Apps UI resource, discovered under {@code mcp/} as a {@code kind: ui}
 * document (roadmap Phase 24, the MCP Apps extension). A UI resource is read-only interactive
 * context: a {@code query-html} / {@code page} definition - same input-free pipeline and security as
 * a route - that server-renders a Hypermedia Components ({@code hc-*}) / htmx fragment, served over
 * the Model Context Protocol by a {@code ui://} {@code uri} with the {@code text/html;profile=mcp-app}
 * content type. A {@code kind: tool} document references one via {@code ui:} so a tool's result is
 * presented through it instead of as plain JSON.
 *
 * @param source      the source file path within the app home
 * @param definition  the parsed definition (reuses the route model)
 * @param description the resource description for the MCP client, or null
 * @param uri         the stable {@code ui://} resource uri the client reads and tools link to
 * @param ui          rendering hints emitted as the resource's {@code _meta.ui}
 */
public record UiResourceFile(Path source, RouteDefinition definition, String description,
        String uri,
        UiSpec ui) {

    /** The fixed MCP Apps content type tagging a UI resource's HTML (SEP-1865). */
    public static final String MIME_TYPE = "text/html;profile=mcp-app";

    public UiResourceFile {
        ui = ui == null ? UiSpec.EMPTY : ui;
    }

    /** The MCP Apps UI content type ({@value #MIME_TYPE}). */
    public String mimeType() {
        return MIME_TYPE;
    }
}
