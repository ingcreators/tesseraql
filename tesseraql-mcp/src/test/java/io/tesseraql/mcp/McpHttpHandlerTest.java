package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpHttpHandlerTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}";

    private McpServer server() {
        return McpServer.builder("http", "1.0")
                .tool(McpTool.builder("echo")
                        .handler(args -> McpToolResult.text(args.path("text").asText()))
                        .build())
                .build();
    }

    private McpHttpHandler.Request post(String body, String session) {
        return new McpHttpHandler.Request("POST", null, session, null, body);
    }

    @Test
    void initializeMintsASessionThatLaterCallsReuse() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);

        McpHttpHandler.Response init = handler.handle(post(INIT, null));
        assertThat(init.status()).isEqualTo(200);
        String session = init.headers().get(McpHttpHandler.SESSION_HEADER);
        assertThat(session).isNotBlank();
        assertThat(init.body()).contains("\"protocolVersion\"");

        McpHttpHandler.Response call = handler.handle(post(CALL, session));
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.body()).contains("\"text\":\"hi\"");
    }

    @Test
    void anUnknownSessionIsRejected() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        assertThat(handler.handle(post(CALL, "bogus")).status()).isEqualTo(404);
    }

    @Test
    void deleteEndsTheSession() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        String session = handler.handle(post(INIT, null)).headers()
                .get(McpHttpHandler.SESSION_HEADER);

        McpHttpHandler.Response deleted = handler
                .handle(new McpHttpHandler.Request("DELETE", null, session, null, ""));
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(handler.handle(post(CALL, session)).status()).isEqualTo(404);
    }

    @Test
    void getIsNotAllowed() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response response = handler
                .handle(new McpHttpHandler.Request("GET", null, null, null, ""));
        assertThat(response.status()).isEqualTo(405);
        assertThat(response.headers()).containsKey("Allow");
    }

    @Test
    void aNotificationIsAccepted() {
        McpHttpHandler handler = new McpHttpHandler(server(), null);
        McpHttpHandler.Response response = handler.handle(
                post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null));
        assertThat(response.status()).isEqualTo(202);
    }

    @Test
    void aConfiguredAuthenticatorGatesEveryRequest() {
        McpAuthenticator auth = header -> {
            if (!"Bearer good".equals(header)) {
                throw new IllegalArgumentException("bad token");
            }
        };
        McpHttpHandler handler = new McpHttpHandler(server(), auth);
        assertThat(handler.requiresAuth()).isTrue();

        McpHttpHandler.Response rejected = handler.handle(post(INIT, null));
        assertThat(rejected.status()).isEqualTo(401);
        assertThat(rejected.headers()).containsKey("WWW-Authenticate");

        McpHttpHandler.Response accepted = handler
                .handle(new McpHttpHandler.Request("POST", "Bearer good", null, null, INIT));
        assertThat(accepted.status()).isEqualTo(200);
    }
}
