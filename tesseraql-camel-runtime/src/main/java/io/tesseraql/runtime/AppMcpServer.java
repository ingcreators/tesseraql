package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.mcp.McpCallContext;
import io.tesseraql.mcp.McpResource;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.mcp.McpTool;
import io.tesseraql.mcp.McpToolResult;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ResourceFile;
import io.tesseraql.yaml.manifest.ToolFile;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;

/**
 * Builds the {@link McpServer} that serves an application's declared MCP tools and resources
 * (roadmap Phase 24). Each tool's handler bridges to its compiled {@code direct:mcp.<id>} route, and
 * each resource's reader to its {@code direct:mcp.resource.<id>} route, through a
 * {@link ProducerTemplate}, passing the call's {@code Authorization} header so the route's own
 * authentication, authorization, input validation, and SQL all run unchanged - a tool/resource is
 * governed exactly like a route. The route renders a JSON body; a non-2xx response (an auth,
 * validation, or conflict failure handled by the route's error renderer) becomes an MCP tool error
 * or, for a resource, a {@code resources/read} JSON-RPC error.
 */
final class AppMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppMcpServer() {
    }

    static McpServer build(AppManifest manifest, String appName, ProducerTemplate producer) {
        McpServer.Builder builder = McpServer.builder(appName, appVersion(manifest))
                .instructions("MCP tools and resources served by the " + appName + " application.");
        for (ToolFile tool : manifest.tools()) {
            String endpoint = "direct:mcp." + tool.definition().id();
            builder.tool(McpTool.builder(tool.definition().id())
                    .description(tool.description())
                    .inputSchema(McpInputSchema.fromInputs(tool.definition().input()))
                    .handler((arguments, context) -> invoke(producer, endpoint, arguments, context))
                    .build());
        }
        for (ResourceFile resource : manifest.resources()) {
            String endpoint = "direct:mcp.resource." + resource.definition().id();
            builder.resource(McpResource.builder(resource.uri(), resource.definition().id())
                    .description(resource.description())
                    .mimeType(resource.effectiveMimeType())
                    .reader(context -> read(producer, endpoint, context))
                    .build());
        }
        return builder.build();
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
