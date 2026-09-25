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

    /** A ceiling small enough to cross without a ten-megabyte body. */
    private static final long CEILING = 256;

    @BeforeEach
    void start() throws Exception {
        McpServer server = McpServer.builder("http-e2e", "1.0")
                .tool(McpTool.builder("echo")
                        .handler((args, ctx) -> McpToolResult.text(args.path("text").asString()))
                        .build())
                .build();
        transport = new HttpTransport(new McpHttpHandler(server, null), "127.0.0.1", 0, "/mcp",
                CEILING);
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

    /**
     * The headers the handler judges reach it from the JDK server: a web page's request —
     * foreign origin, text/plain, no session — is refused on the wire, where it ran the write
     * tools (docs/audit-low-leads.md, G1).
     */
    @Test
    void aWebPagesRequestIsRefusedOverRealHttp() throws Exception {
        HttpResponse<String> foreign = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Origin", "http://evil.example")
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).contains("TQL-MCP-4265");

        HttpResponse<String> plain = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(plain.statusCode()).isEqualTo(415);
        assertThat(plain.body()).contains("TQL-MCP-4266");
    }

    /**
     * The body is read to the ceiling and no further — it was buffered whole before anything
     * looked at it, so a request's size was the caller's choice. A declared length past the
     * ceiling is refused before a byte is read; an undeclared (chunked) one at the first byte
     * past it.
     */
    @Test
    void aBodyPastTheCeilingIsRefused() throws Exception {
        String oversized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"pad\":\"" + "x".repeat((int) CEILING) + "\"}}";
        HttpResponse<String> declared = post(oversized, null);
        assertThat(declared.statusCode()).isEqualTo(413);
        assertThat(declared.body()).contains("TQL-MCP-4270");

        HttpResponse<String> chunked = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new java.io.ByteArrayInputStream(
                                        oversized.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).isEqualTo(413);

        // And a body under the ceiling is served as before.
        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                null).statusCode()).isEqualTo(200);
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
