package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * The fully loaded TesseraQL application: resolved configuration, compiled-ready route files, and
 * a reproducibility index (design ch. 3, 22.20).
 *
 * @param appHome     the external app home directory (design ch. 2.5, 4)
 * @param config      merged, placeholder-resolving configuration
 * @param routes      route files discovered under {@code web/}
 * @param jobs        job files discovered under {@code batch/}
 * @param tools       application-declared MCP tool files discovered under {@code mcp/}
 * @param resources   application-declared MCP resource files discovered under {@code mcp/}
 * @param uiResources application-declared MCP Apps UI resource files discovered under {@code mcp/}
 * @param consumers   queue-consume route files discovered under {@code consume/} (roadmap Phase 27)
 * @param scopes      data-scope definitions discovered under {@code scope/} (roadmap Phase 29)
 * @param workflows   approval-workflow definitions discovered under {@code workflow/} (Phase 28)
 * @param attachments attachment definitions discovered under {@code attachments/} (Phase 30)
 * @param index       checksum index of the manifest source files
 */
public record AppManifest(Path appHome, AppConfig config, List<RouteFile> routes,
        List<JobFile> jobs, List<ToolFile> tools, List<ResourceFile> resources,
        List<UiResourceFile> uiResources, List<RouteFile> consumers, List<ScopeFile> scopes,
        List<WorkflowFile> workflows, List<AttachmentFile> attachments, ManifestIndex index) {

    public AppManifest {
        routes = List.copyOf(routes);
        jobs = List.copyOf(jobs);
        tools = List.copyOf(tools);
        resources = List.copyOf(resources);
        uiResources = List.copyOf(uiResources);
        consumers = List.copyOf(consumers);
        scopes = List.copyOf(scopes);
        workflows = List.copyOf(workflows);
        attachments = List.copyOf(attachments);
    }
}
