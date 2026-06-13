package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;

/**
 * An application-declared MCP resource, discovered under {@code mcp/} as a {@code kind: resource}
 * document (roadmap Phase 24). A resource is read-only context an agent attaches: a
 * {@code query-json} definition - same input-free SQL and security as a route - exposed over the
 * Model Context Protocol by its {@code uri} instead of an HTTP path. The {@code description} (read
 * from the YAML) is the hint the model uses to decide whether to attach it; the {@code mimeType}
 * (defaulting to {@code application/json}) tags the contents returned on {@code resources/read}.
 *
 * @param source      the source file path within the app home
 * @param definition  the parsed definition (reuses the route model)
 * @param description the resource description for the MCP client, or null
 * @param uri         the stable resource uri the client reads
 * @param mimeType    the declared content type, or null to default to {@code application/json}
 */
public record ResourceFile(Path source, RouteDefinition definition, String description, String uri,
        String mimeType) {

    /** The declared content type, defaulting to {@code application/json}. */
    public String effectiveMimeType() {
        return mimeType == null || mimeType.isBlank() ? "application/json" : mimeType;
    }
}
