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
 * <p>An optional {@code uiResource} (the document's {@code ui:} field) is the {@code ui://} uri of a
 * UI resource that presents the tool's result (the MCP Apps extension): the runtime advertises it as
 * the tool's {@code _meta.ui.resourceUri}, so a host renders the linked {@link UiResourceFile}
 * fragment instead of showing the raw JSON.
 *
 * @param source      the source file path within the app home
 * @param definition  the parsed definition (reuses the route model)
 * @param description the tool description for the MCP client, or null
 * @param uiResource  the {@code ui://} uri of the linked UI resource, or null
 */
public record ToolFile(Path source, RouteDefinition definition, String description,
        String uiResource) {
}
