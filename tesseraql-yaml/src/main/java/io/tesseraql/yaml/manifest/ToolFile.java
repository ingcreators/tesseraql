package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;

/**
 * An application-declared MCP tool, discovered under {@code mcp/} (roadmap Phase 24 follow-on). A
 * tool is a {@code query-json} or {@code command-json} definition exposed over the Model Context
 * Protocol instead of HTTP: same recipe, input constraints, SQL, and security as a route - only the
 * entry point differs. The {@code description} (read from the YAML) is the natural-language hint the
 * connecting model uses to decide when to call the tool.
 *
 * @param source      the source file path within the app home
 * @param definition  the parsed definition (reuses the route model)
 * @param description the tool description for the MCP client, or null
 */
public record ToolFile(Path source, RouteDefinition definition, String description) {
}
