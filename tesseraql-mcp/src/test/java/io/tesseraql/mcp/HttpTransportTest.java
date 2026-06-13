package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

    private HttpTransport transport;
    private String url;

    @BeforeEach
    void start() throws Exception {
        McpServer server = McpServer.builder("http-e2e", "1.0")
                .tool(McpTool.builder("echo")
                        .handler(args -> McpToolResult.text(args.path("text").asText()))
                        .build())
                .build();
        transport = new HttpTransport(new McpHttpHandler(server, null), "127.0.0.1", 0, "/mcp");
        transport.start();
        url = transport.url();
    }

    @AfterEach
    void stop() {
        transport.stop();
    }

    private HttpResponse<String> post(String body, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header(McpHttpHandler.SESSION_HEADER, session);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheInitializeHandshakeAndAToolCallOverRealHttp() throws Exception {
        HttpResponse<String> init = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue(McpHttpHandler.SESSION_HEADER).orElseThrow();
        assertThat(init.body()).contains("\"serverInfo\"");

        HttpResponse<String> call = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"over-http\"}}}",
                session);
        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.body()).contains("over-http");
    }
}
