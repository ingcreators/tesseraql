package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class McpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private McpServer server() {
        return McpServer.builder("test-server", "9.9")
                .instructions("be helpful")
                .tool(McpTool.builder("echo")
                        .description("echoes its text back")
                        .inputSchema(McpSchema.object().required("text", "string", "the text"))
                        .handler((args, ctx) -> McpToolResult.text(args.path("text").asText()))
                        .build())
                .tool(McpTool.builder("info")
                        .handler((args, ctx) -> McpToolResult.json(Map.of("answer", 42)))
                        .build())
                .tool(McpTool.builder("boom")
                        .handler((args, ctx) -> {
                            throw new IllegalStateException("kaboom");
                        })
                        .build())
                .tool(McpTool.builder("denied")
                        .handler((args, ctx) -> {
                            throw new TqlException(new TqlErrorCode(TqlDomain.MCP, 4001), "nope");
                        })
                        .build())
                .resource(McpResource.builder("tesseraql://catalog", "catalog")
                        .description("the product catalog")
                        .mimeType("application/json")
                        .reader(ctx -> "{\"items\":3}")
                        .build())
                .resource(McpResource.builder("tesseraql://broken", "broken")
                        .reader(ctx -> {
                            throw new TqlException(new TqlErrorCode(TqlDomain.MCP, 4001), "denied");
                        })
                        .build())
                .build();
    }

    private JsonNode call(String json) {
        Optional<JsonNode> response = server().handle(read(json));
        return response.orElse(null);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void initializeNegotiatesAKnownProtocolVersionAndAdvertisesTools() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}").get("result");
        assertThat(result.get("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(result.get("serverInfo").get("name").asText()).isEqualTo("test-server");
        assertThat(result.get("capabilities").get("tools")).isNotNull();
        assertThat(result.get("instructions").asText()).isEqualTo("be helpful");
    }

    @Test
    void initializeFallsBackToTheLatestVersionForAnUnknownRequest() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}").get("result");
        assertThat(result.get("protocolVersion").asText()).isEqualTo("2025-06-18");
    }

    @Test
    void toolsListReturnsEveryRegisteredToolWithItsSchema() {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .get("result").get("tools");
        assertThat(tools).hasSize(4);
        JsonNode echo = tools.get(0);
        assertThat(echo.get("name").asText()).isEqualTo("echo");
        assertThat(echo.get("inputSchema").get("required").get(0).asText()).isEqualTo("text");
    }

    @Test
    void toolsCallRunsTheHandlerAndReturnsTextContent() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}").get("result");
        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.get("content").get(0).get("text").asText()).isEqualTo("hi");
    }

    @Test
    void toolsCallSerializesStructuredResultsBothWays() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"info\"}}").get("result");
        assertThat(result.get("structuredContent").get("answer").asInt()).isEqualTo(42);
        assertThat(result.get("content").get(0).get("type").asText()).isEqualTo("text");
    }

    @Test
    void anUnknownToolIsAnInvalidParamsError() {
        JsonNode error = call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\"}}").get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void aThrowingHandlerBecomesAToolErrorResultNotAProtocolError() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"boom\"}}");
        assertThat(response.has("error")).isFalse();
        JsonNode result = response.get("result");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("kaboom");
    }

    @Test
    void aTqlExceptionSurfacesItsErrorCodeInTheToolError() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"denied\"}}").get("result");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText())
                .contains("TQL-MCP-4001").contains("nope");
    }

    @Test
    void aNotificationProducesNoResponse() {
        assertThat(call("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    }

    @Test
    void anUnknownMethodIsMethodNotFound() {
        JsonNode error = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"prompts/list\"}")
                .get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void initializeAdvertisesResourcesOnlyWhenSomeAreRegistered() {
        JsonNode withResources = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                .get("result").get("capabilities");
        assertThat(withResources.has("resources")).isTrue();
        assertThat(withResources.get("resources").get("subscribe").asBoolean()).isFalse();

        McpServer toolsOnly = McpServer.builder("t", "1")
                .tool(McpTool.builder("noop").handler((a, c) -> McpToolResult.text("")).build())
                .build();
        JsonNode caps = toolsOnly
                .handle(read("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .orElseThrow().get("result").get("capabilities");
        assertThat(caps.has("resources")).isFalse();
    }

    @Test
    void resourcesListReturnsEveryRegisteredResource() {
        JsonNode resources = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}")
                .get("result").get("resources");
        assertThat(resources).hasSize(2);
        JsonNode catalog = resources.get(0);
        assertThat(catalog.get("uri").asText()).isEqualTo("tesseraql://catalog");
        assertThat(catalog.get("name").asText()).isEqualTo("catalog");
        assertThat(catalog.get("mimeType").asText()).isEqualTo("application/json");
    }

    @Test
    void resourcesReadReturnsContentsTaggedWithTheResourceUriAndMimeType() {
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"tesseraql://catalog\"}}").get("result");
        JsonNode entry = result.get("contents").get(0);
        assertThat(entry.get("uri").asText()).isEqualTo("tesseraql://catalog");
        assertThat(entry.get("mimeType").asText()).isEqualTo("application/json");
        assertThat(entry.get("text").asText()).isEqualTo("{\"items\":3}");
    }

    @Test
    void resourcesReadOfAnUnknownUriIsTheResourceNotFoundError() {
        JsonNode error = call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"tesseraql://missing\"}}").get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32002);
    }

    @Test
    void aFailingResourceReadIsAJsonRpcErrorCarryingTheCode() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"tesseraql://broken\"}}");
        assertThat(response.has("result")).isFalse();
        JsonNode error = response.get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32603);
        assertThat(error.get("message").asText()).contains("TQL-MCP-4001").contains("denied");
    }

    @Test
    void resourceTemplatesListIsAnEmptyList() {
        JsonNode templates = call("{\"jsonrpc\":\"2.0\",\"id\":6,"
                + "\"method\":\"resources/templates/list\"}")
                .get("result").get("resourceTemplates");
        assertThat(templates).isEmpty();
    }

    @Test
    void pingAnswersWithAnEmptyResult() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}");
        assertThat(response.has("error")).isFalse();
        assertThat(response.get("result").isObject()).isTrue();
    }
}
