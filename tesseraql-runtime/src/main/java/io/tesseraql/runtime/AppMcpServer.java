package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tesseraql.mcp.McpCallContext;
import io.tesseraql.mcp.McpPrompt;
import io.tesseraql.mcp.McpPromptResult;
import io.tesseraql.mcp.McpResource;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.mcp.McpTool;
import io.tesseraql.mcp.McpToolResult;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.PromptFile;
import io.tesseraql.yaml.manifest.ResourceFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.manifest.UiResourceFile;
import io.tesseraql.yaml.model.UiSpec;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link McpServer} that serves an application's declared MCP tools, resources, and MCP
 * Apps UI resources (roadmap Phase 24). Each tool's handler bridges to its compiled
 * {@code direct:mcp.<id>} route, each resource's reader to its {@code direct:mcp.resource.<id>}
 * route, and each UI resource's reader to its {@code direct:mcp.ui.<id>} route, through a
 * {@link RoutePipelines}, passing the call's {@code Authorization} header so the route's own
 * authentication, authorization, input validation, and SQL all run unchanged - a tool/resource is
 * governed exactly like a route. A tool/resource route renders a JSON body and a UI route an
 * {@code hc-*} HTML fragment; a non-2xx response (an auth, validation, or conflict failure handled
 * by the route's error renderer) becomes an MCP tool error or, for a (UI) resource, a
 * {@code resources/read} JSON-RPC error.
 *
 * <p>A prompt is a route too (docs/prompt-as-recipe.md): its handler sends to
 * {@code direct:mcp.prompt.<id>} and wraps the rendered text as one {@code user} message, so a
 * prompt that reads data is governed like everything else here. All four primitives reach their
 * route through one sender, {@code call}, and differ only in how they wrap what it returns.
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
    static McpServer build(String appName, List<AppManifest> apps, RoutePipelines pipelines) {
        McpServer.Builder builder = McpServer.builder(appName, appVersion(apps.get(0)))
                .instructions("MCP tools and resources served by the " + appName + " application.");
        // Negotiate the MCP Apps UI extension when any hosted app serves a ui:// resource (SEP-1865).
        if (apps.stream().anyMatch(app -> !app.uiResources().isEmpty())) {
            ObjectNode capability = MAPPER.createObjectNode();
            capability.putArray("mimeTypes").add(UiResourceFile.MIME_TYPE);
            builder.extension("io.modelcontextprotocol/ui", capability);
        }
        for (AppManifest manifest : apps) {
            register(builder, manifest, pipelines);
        }
        return builder.build();
    }

    /** Registers one app's tools, resources, and UI resources on the server builder. */
    private static void register(McpServer.Builder builder, AppManifest manifest,
            RoutePipelines pipelines) {
        for (ToolFile tool : manifest.tools()) {
            String routeId = "mcp." + tool.definition().id();
            McpTool.Builder toolBuilder = McpTool.builder(tool.definition().id())
                    .description(tool.description())
                    .inputSchema(McpInputSchema.fromInputs(tool.definition().input()))
                    .handler(
                            (arguments, context) -> invoke(pipelines, routeId, arguments, context));
            // A tool that renders into a UI resource advertises the link as _meta.ui.resourceUri.
            if (tool.uiResource() != null && !tool.uiResource().isBlank()) {
                toolBuilder.meta(toolMeta(tool.uiResource()));
            }
            builder.tool(toolBuilder.build());
        }
        for (ResourceFile resource : manifest.resources()) {
            String routeId = "mcp.resource." + resource.definition().id();
            builder.resource(McpResource.builder(resource.uri(), resource.definition().id())
                    .description(resource.description())
                    .mimeType(resource.effectiveMimeType())
                    .reader(context -> read(pipelines, routeId, context))
                    .build());
        }
        for (UiResourceFile ui : manifest.uiResources()) {
            String routeId = "mcp.ui." + ui.definition().id();
            McpResource.Builder resourceBuilder = McpResource
                    .builder(ui.uri(), ui.definition().id())
                    .description(ui.description())
                    .mimeType(ui.mimeType())
                    .reader(context -> read(pipelines, routeId, context));
            ObjectNode meta = uiMeta(ui.ui());
            if (meta != null) {
                resourceBuilder.meta(meta);
            }
            builder.resource(resourceBuilder.build());
        }
        for (PromptFile prompt : manifest.prompts()) {
            McpPrompt.Builder promptBuilder = McpPrompt.builder(prompt.id())
                    .description(prompt.description());
            for (PromptFile.Argument argument : prompt.arguments()) {
                promptBuilder.argument(argument.name(), argument.description(),
                        argument.required());
            }
            // A prompt is a route like its three siblings (docs/prompt-as-recipe.md): the message
            // comes from the compiled route, which runs its own security, binding and SQL.
            String routeId = "mcp.prompt." + prompt.definition().id();
            promptBuilder.handler(
                    (arguments, context) -> getPrompt(pipelines, routeId, arguments, context));
            builder.prompt(promptBuilder.build());
        }
    }

    /**
     * Gets a prompt by sending the supplied arguments to its compiled route and wrapping the
     * rendered text as one {@code user} message. Like a resource read, a thrown exception (the
     * send failed, or the route's error renderer set a non-2xx status) propagates to the server,
     * which turns it into a {@code prompts/get} JSON-RPC error.
     */
    private static McpPromptResult getPrompt(RoutePipelines pipelines, String routeId,
            Map<String, String> arguments, McpCallContext context) {
        return McpPromptResult.user(requireOk(call(pipelines, routeId, arguments, context),
                "prompt"));
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
    private static String read(RoutePipelines pipelines, String routeId, McpCallContext context) {
        return requireOk(call(pipelines, routeId, Map.of(), context), "resource");
    }

    @SuppressWarnings("unchecked")
    private static McpToolResult invoke(RoutePipelines pipelines, String routeId,
            JsonNode arguments, McpCallContext context) {
        Map<String, Object> input = arguments == null || arguments.isNull()
                ? Map.of()
                : MAPPER.convertValue(arguments, Map.class);
        Outcome outcome = call(pipelines, routeId, input, context);
        if (outcome.failed()) {
            return McpToolResult.error(outcome.failure());
        }
        if (outcome.errored()) {
            return McpToolResult.error(outcome.body() == null
                    ? "tool error (" + outcome.status() + ")"
                    : outcome.body());
        }
        try {
            return McpToolResult.json(MAPPER.readTree(
                    outcome.body() == null ? "null" : outcome.body()));
        } catch (Exception ex) {
            return McpToolResult.text(outcome.body() == null ? "" : outcome.body());
        }
    }

    /**
     * The one sender every MCP primitive reaches its route through: the declared body plus the
     * call's {@code Authorization} header, so the route's own authentication, authorization,
     * input validation and SQL all run. What comes back is the exchange's outcome, and each
     * primitive wraps it - a tool as an {@link McpToolResult}, a resource or UI resource as its
     * body, a prompt as one {@code user} message.
     *
     * <p>Prompts reaching the wire this way is what gives a prompt security at all: the older,
     * routeless form passed no {@code Authorization} anywhere, having no route to pass it to
     * (docs/prompt-as-recipe.md decision 7).
     */
    private static Outcome call(RoutePipelines pipelines, String routeId, Object body,
            McpCallContext context) {
        Exchange out = pipelines.run(routeId, exchange -> {
            exchange.getMessage().setBody(body);
            if (context.authorization() != null) {
                exchange.getMessage().setHeader("Authorization", context.authorization());
            }
        }).orElse(null);
        if (out == null) {
            return new Outcome(null, null, new IllegalStateException(
                    "No compiled route '" + routeId + "' behind this MCP primitive"));
        }
        if (out.getException() != null) {
            return new Outcome(null, null, out.getException());
        }
        return new Outcome(out.getMessage().getBody(String.class),
                out.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE, Integer.class), null);
    }

    /**
     * The body of a send that succeeded, for the two primitives that answer with a JSON-RPC error
     * rather than a result: a failure becomes an {@link IllegalStateException} the server turns
     * into that error. {@code what} names the primitive in the fallback message a status with no
     * body leaves.
     */
    private static String requireOk(Outcome outcome, String what) {
        if (outcome.failed()) {
            throw new IllegalStateException(outcome.failure());
        }
        if (outcome.errored()) {
            throw new IllegalStateException(outcome.body() == null
                    ? what + " error (" + outcome.status() + ")"
                    : outcome.body());
        }
        return outcome.body() == null ? "" : outcome.body();
    }

    /**
     * One send's outcome: the rendered body and its status, or the exception the send failed with.
     *
     * @param body      the rendered body, or null when the send failed or the route set none
     * @param status    the response status the route set, or null when it set none
     * @param exception the exception the send failed with, or null
     */
    private record Outcome(String body, Integer status, Throwable exception) {

        /** Whether the send itself failed, before any response was rendered. */
        boolean failed() {
            return exception != null;
        }

        /** Whether the route's error renderer answered — a handled auth, validation or conflict. */
        boolean errored() {
            return status != null && status >= 400;
        }

        /** The failure text, which is the exception's message when it has one. */
        String failure() {
            return exception.getMessage() != null ? exception.getMessage() : exception.toString();
        }
    }

    private static String appVersion(AppManifest manifest) {
        return manifest.config().getString("tesseraql.app.version").orElse("0.0.0");
    }
}
