package io.tesseraql.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The MCP stdio transport: newline-delimited JSON-RPC over a stream pair (an agent launches the
 * server as a subprocess and talks over its stdin/stdout). Each message is one line of JSON with no
 * embedded newlines, per the MCP stdio spec.
 *
 * <p>The stream the transport writes to carries protocol messages only - a caller wiring this to
 * {@code System.out} must keep all logging on {@code System.err}, or it corrupts the stream.
 */
public final class StdioTransport {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpServer server;
    private final InputStream in;
    private final OutputStream out;

    public StdioTransport(McpServer server, InputStream in, OutputStream out) {
        this.server = server;
        this.in = in;
        this.out = out;
    }

    /** Serves until end-of-input (the peer closes stdin). Blocks the calling thread. */
    public void serve() throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Optional<JsonNode> response = dispatch(line);
            if (response.isPresent()) {
                writer.write(mapper.writeValueAsString(response.get()));
                writer.write('\n');
                writer.flush();
            }
        }
    }

    private Optional<JsonNode> dispatch(String line) {
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (JsonProcessingException ex) {
            return Optional.of(parseError(ex.getOriginalMessage()));
        }
        return server.handle(message);
    }

    private JsonNode parseError(String detail) {
        var response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", mapper.nullNode());
        response.putObject("error")
                .put("code", McpServer.PARSE_ERROR)
                .put("message", "Parse error: " + detail);
        return response;
    }
}
