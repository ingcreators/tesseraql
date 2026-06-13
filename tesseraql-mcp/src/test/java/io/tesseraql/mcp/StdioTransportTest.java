package io.tesseraql.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StdioTransportTest {

    private McpServer server() {
        return McpServer.builder("stdio", "1.0")
                .tool(McpTool.builder("echo")
                        .handler((args, ctx) -> McpToolResult.text(args.path("text").asText()))
                        .build())
                .build();
    }

    private String serve(String input) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StdioTransport(server(), in, out).serve();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void answersRequestsLineByLineAndSkipsNotificationsAndBlankLines() throws Exception {
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}

                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hey"}}}
                """;
        String[] lines = serve(input).strip().split("\n");
        // One reply for initialize, none for the blank line or the notification, one for the call.
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("\"protocolVersion\"").doesNotContain("\n");
        assertThat(lines[1]).contains("\"text\":\"hey\"");
    }

    @Test
    void aMalformedLineYieldsAParseError() throws Exception {
        String output = serve("not json at all\n");
        assertThat(output).contains("\"code\":" + McpServer.PARSE_ERROR);
    }
}
