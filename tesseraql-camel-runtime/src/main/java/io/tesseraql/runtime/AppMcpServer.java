package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.mcp.McpCallContext;
import io.tesseraql.mcp.McpResource;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.mcp.McpTool;
import io.tesseraql.mcp.McpToolResult;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ResourceFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.manifest.UiResourceFile;
import io.tesseraql.yaml.model.UiSpec;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;

/**
 * Builds the {@link McpServer} that serves an application's declared MCP tools, resources, and MCP
 * Apps UI resources (roadmap Phase 24). Each tool's handler bridges to its compiled
 * {@code direct:mcp.<id>} route, each resource's reader to its {@code direct:mcp.resource.<id>}
 * route, and each UI resource's reader to its {@code direct:mcp.ui.<id>} route, through a
 * {@link ProducerTemplate}, passing the call's {@code Authorization} header so the route's own
 * authentication, authorization, input validation, and SQL all run unchanged - a tool/resource is
 * governed exactly like a route. A tool/resource route renders a JSON body and a UI route an
 * {@code hc-*} HTML fragment; a non-2xx response (an auth, validation, or conflict failure handled
 * by the route's error renderer) becomes an MCP tool error or, for a (UI) resource, a
 * {@code resources/read} JSON-RPC error.
 *
 * <p>A tool that links to a UI resource (its {@code ui:} field) advertises the link as the tool's
 * {@code _meta.ui.resourceUri}, the UI resource carries its {@code _meta.ui} rendering hints, and the
 * MCP Apps extension is negotiated under {@code capabilities.extensions["io.modelcontextprotocol/ui"]}
 * when the app serves any UI resource.
 *
 * <p>The server serves the MCP surface of the main app and every mounted/system app (design ch. 32,
 * roadmap Phase 24 mounted-app tools) from one endpoint: each app's tools, resources, and UI
 * resources are registered together. Tool names and resource uris are unique across apps (the host
 * runs {@link SystemApps#requireNoRouteConflicts} before building this server), and a tool/resource
 * route is the same {@code direct:mcp.*} route regardless of which app declared it, so a handler
 * sends to it the same way.
 */
final class AppMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppMcpServer() {
    }

    /**
     * Builds the server serving the MCP surface of every app in {@code apps} (the main app first,
     * then each mounted app). The server's name and version come from the main app; mounted apps
     * share its config, so they carry the same version.
     */
    static McpServer build(String appName, List<AppManifest> apps, ProducerTemplate producer) {
        McpServer.Builder builder = McpServer.builder(appName, appVersion(apps.get(0)))
                .instructions("MCP tools and resources served by the " + appName + " application.");
        // Negotiate the MCP Apps UI extension when any hosted app serves a ui:// resource (SEP-1865).
        if (apps.stream().anyMatch(app -> !app.uiResources().isEmpty())) {
            ObjectNode capability = MAPPER.createObjectNode();
            capability.putArray("mimeTypes").add(UiResourceFile.MIME_TYPE);
            builder.extension("io.modelcontextprotocol/ui", capability);
        }
        for (AppManifest manifest : apps) {
            register(builder, manifest, producer);
        }
        return builder.build();
    }

    /** Registers one app's tools, resources, and UI resources on the server builder. */
    private static void register(McpServer.Builder builder, AppManifest manifest,
            ProducerTemplate producer) {
        for (ToolFile tool : manifest.tools()) {
            String endpoint = "direct:mcp." + tool.definition().id();
            McpTool.Builder toolBuilder = McpTool.builder(tool.definition().id())
                    .description(tool.description())
                    .inputSchema(McpInputSchema.fromInputs(tool.definition().input()))
                    .handler(
                            (arguments, context) -> invoke(producer, endpoint, arguments, context));
            // A tool that renders into a UI resource advertises the link as _meta.ui.resourceUri.
            if (tool.uiResource() != null && !tool.uiResource().isBlank()) {
                toolBuilder.meta(toolMeta(tool.uiResource()));
            }
            builder.tool(toolBuilder.build());
        }
        for (ResourceFile resource : manifest.resources()) {
            String endpoint = "direct:mcp.resource." + resource.definition().id();
            builder.resource(McpResource.builder(resource.uri(), resource.definition().id())
                    .description(resource.description())
                    .mimeType(resource.effectiveMimeType())
                    .reader(context -> read(producer, endpoint, context))
                    .build());
        }
        for (UiResourceFile ui : manifest.uiResources()) {
            String endpoint = "direct:mcp.ui." + ui.definition().id();
            McpResource.Builder resourceBuilder = McpResource
                    .builder(ui.uri(), ui.definition().id())
                    .description(ui.description())
                    .mimeType(ui.mimeType())
                    .reader(context -> read(producer, endpoint, context));
            ObjectNode meta = uiMeta(ui.ui());
            if (meta != null) {
                resourceBuilder.meta(meta);
            }
            builder.resource(resourceBuilder.build());
        }
    }

    /** A linking tool's {@code _meta.ui}: the UI resource it renders into, visible to model and app. */
    private static ObjectNode toolMeta(String uiResourceUri) {
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode ui = meta.putObject("ui");
        ui.put("resourceUri", uiResourceUri);
        ui.putArray("visibility").add("model").add("app");
        return meta;
    }

    /** A UI resource's {@code _meta.ui} rendering hints (prefers-border, csp), or null when empty. */
    private static ObjectNode uiMeta(UiSpec ui) {
        if (ui == null || ui.isEmpty()) {
            return null;
        }
        ObjectNode meta = MAPPER.createObjectNode();
        ObjectNode node = meta.putObject("ui");
        if (ui.prefersBorder() != null) {
            node.put("prefersBorder", ui.prefersBorder());
        }
        if (!ui.cspConnectDomains().isEmpty() || !ui.cspResourceDomains().isEmpty()) {
            ObjectNode csp = node.putObject("csp");
            if (!ui.cspConnectDomains().isEmpty()) {
                ui.cspConnectDomains().forEach(csp.putArray("connectDomains")::add);
            }
            if (!ui.cspResourceDomains().isEmpty()) {
                ui.cspResourceDomains().forEach(csp.putArray("resourceDomains")::add);
            }
        }
        return meta;
    }

    /**
     * Reads a resource by sending to its read-only route and returning the rendered JSON body. A
     * thrown exception (the route's error renderer set a non-2xx status, or the send itself failed)
     * propagates to the server, which turns it into a {@code resources/read} JSON-RPC error.
     */
    private static String read(ProducerTemplate producer, String endpoint, McpCallContext context) {
        Exchange out = producer.send(endpoint, exchange -> {
            exchange.getMessage().setBody(Map.of());
            if (context.authorization() != null) {
                exchange.getMessage().setHeader("Authorization", context.authorization());
            }
        });
        if (out.getException() != null) {
            Throwable cause = out.getException();
            throw new IllegalStateException(cause.getMessage() != null
                    ? cause.getMessage()
                    : cause.toString());
        }
        String body = out.getMessage().getBody(String.class);
        Integer status = out.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        if (status != null && status >= 400) {
            throw new IllegalStateException(
                    body == null ? "resource error (" + status + ")" : body);
        }
        return body == null ? "" : body;
    }

    @SuppressWarnings("unchecked")
    private static McpToolResult invoke(ProducerTemplate producer, String endpoint,
            JsonNode arguments, McpCallContext context) {
        Map<String, Object> input = arguments == null || arguments.isNull()
                ? Map.of()
                : MAPPER.convertValue(arguments, Map.class);
        Exchange out = producer.send(endpoint, exchange -> {
            exchange.getMessage().setBody(input);
            if (context.authorization() != null) {
                exchange.getMessage().setHeader("Authorization", context.authorization());
            }
        });
        if (out.getException() != null) {
            Throwable cause = out.getException();
            return McpToolResult.error(cause.getMessage() != null
                    ? cause.getMessage()
                    : cause.toString());
        }
        String body = out.getMessage().getBody(String.class);
        Integer status = out.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        if (status != null && status >= 400) {
            return McpToolResult.error(body == null ? "tool error (" + status + ")" : body);
        }
        try {
            return McpToolResult.json(MAPPER.readTree(body == null ? "null" : body));
        } catch (Exception ex) {
            return McpToolResult.text(body == null ? "" : body);
        }
    }

    private static String appVersion(AppManifest manifest) {
        return manifest.config().getString("tesseraql.app.version").orElse("0.0.0");
    }
}
